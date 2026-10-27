package org.example.traveljavaserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import org.example.traveljavaserver.common.AppException;
import org.example.traveljavaserver.utils.LLMutils;
import org.example.traveljavaserver.vo.StreamEvent;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class TravelService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(TravelService.class);
  private final LLMutils llm;
  private final DataService data;
  private final ObjectMapper mapper = new ObjectMapper();
  private final ConcurrentHashMap<String, Session> active = new ConcurrentHashMap<>();
  private final ThreadPoolExecutor workers;
  private final ScheduledExecutorService maintenance;
  private final long timeoutMs;
  private final long heartbeatMs;
  private final Semaphore directPlans = new Semaphore(2);
  // Ended streams retain their slot until the terminal transaction has been persisted.
  private final Semaphore acceptedChats;
  private record DirectPlan(long owner, Call call) {}
  private final ConcurrentHashMap<String, DirectPlan> planningCalls = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Long> cancelledRequests = new ConcurrentHashMap<>();

  public TravelService(
      LLMutils llm,
      DataService data,
      @Value("${chat.timeout-ms:180000}") long timeoutMs,
      @Value("${chat.heartbeat-ms:1000}") long heartbeatMs,
      @Value("${chat.workers:4}") int workerCount,
      @Value("${chat.queue-capacity:16}") int queueCapacity) {
    this.llm = llm;
    this.data = data;
    this.timeoutMs = timeoutMs;
    this.heartbeatMs = heartbeatMs;
    acceptedChats = new Semaphore(workerCount + queueCapacity);
    workers =
        new ThreadPoolExecutor(
            workerCount,
            workerCount,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            task -> {
              Thread thread = new Thread(task, "travel-chat-worker");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    maintenance =
        Executors.newSingleThreadScheduledExecutor(
            task -> {
              Thread thread = new Thread(task, "travel-chat-maintenance");
              thread.setDaemon(true);
              return thread;
            });
  }

  @PostConstruct
  void startMaintenance() {
    maintenance.scheduleAtFixedRate(
        this::maintain, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
  }

  public TravelRecommendVO recommend(String city, Integer days, java.math.BigDecimal budget) {
    return recommend(-1, java.util.UUID.randomUUID().toString(), city, days, budget);
  }

  public TravelRecommendVO recommend(long owner, String requestId, String city, Integer days,
      java.math.BigDecimal budget) {
    String key = owner + ":" + requestId;
    if (!directPlans.tryAcquire())
      throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "SERVICE_BUSY", "当前规划请求较多，请稍后重试");
    DirectPlan run = null;
    try (var scope =
        org.example.traveljavaserver.utils.ModelTrace.open(
            java.util.UUID.randomUUID().toString(), "travel-direct-v2", null)) {
      Call call = llm.newJsonChatCall(
                  "你是中文旅游规划师。只输出完整JSON；地点、交通、费用为模型参考建议，不声称已查询地图或实时信息。",
                  buildTravelPrompt(city, budget, days),
                  llm.planningOutputLimit(days));
      run = new DirectPlan(owner, call);
      if (planningCalls.putIfAbsent(key, run) != null)
        throw new AppException(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "该规划请求正在执行");
      if (wasCancelled("plan", key)) call.cancel();
      String response = llm.readCompletion(call);
      String json = response.trim();
      if (json.startsWith("```")) {
        int newline = json.indexOf('\n');
        int end = json.lastIndexOf("```");
        if (newline < 0 || end <= newline) throw invalidPlan();
        json = json.substring(newline + 1, end).trim();
      }
      TravelRecommendVO plan =
          mapper
              .readerFor(TravelRecommendVO.class)
              .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readValue(json);
      if (plan == null
          || !Boolean.TRUE.equals(plan.getSuccess())
          || plan.getCity() == null
          || plan.getCity().isBlank()
          || plan.getDays() == null
          || plan.getTotalBudget() == null
          || !PlanStructure.complete(plan)
          || !org.example.traveljavaserver.knowledge.CityCatalog.canonical(city)
              .equals(org.example.traveljavaserver.knowledge.CityCatalog.canonical(plan.getCity()))
          || !days.equals(plan.getDays())
          || budget.compareTo(plan.getTotalBudget()) != 0) throw invalidPlan();
      for (var day : plan.getDailyItinerary()) {
        for (var slot : java.util.List.of(day.getMorning(), day.getAfternoon(), day.getEvening())) {
          if (slot.getSpot() == null || slot.getSpot().isBlank()) throw invalidPlan();
          slot.setPlaceId(null);
          slot.setLatitude(null);
          slot.setLongitude(null);
          slot.setSourceUrl(null);
          slot.setQueriedAt(null);
          slot.setRoute(null);
        }
      }
      plan.setEvidence(null);
      plan.setAgentEvidence(null);
      plan.setTravelMode(null);
      plan.setEstimatedTotal(null);
      plan.setRemainingBudget(null);
      plan.setCity(city);
      plan.setTotalBudget(budget);
      return plan;
    } catch (CancellationException e) {
      throw new AppException(HttpStatus.CONFLICT, "PLANNING_CANCELLED", "规划已取消");
    } catch (IOException e) {
      throw invalidPlan();
    } finally {
      if (run != null) planningCalls.remove(key, run);
      directPlans.release();
    }
  }

  public boolean cancelPlan(long owner, String requestId) {
    String key = owner + ":" + requestId;
    DirectPlan run = planningCalls.get(key);
    if (run != null) { run.call().cancel(); return true; }
    if (!rememberCancellation("plan", key)) return false;
    run = planningCalls.get(key);
    if (run != null) run.call().cancel();
    return true;
  }

  private boolean rememberCancellation(String kind, String key) {
    synchronized (cancelledRequests) {
      long now = System.nanoTime();
      cancelledRequests.entrySet().removeIf(e -> e.getValue() <= now);
      String scoped = kind + ":" + key;
      if (cancelledRequests.size() >= 128 && !cancelledRequests.containsKey(scoped)) return false;
      cancelledRequests.put(scoped, now + TimeUnit.SECONDS.toNanos(180));
      return true;
    }
  }

  private boolean wasCancelled(String kind, String key) {
    Long deadline = cancelledRequests.get(kind + ":" + key);
    return deadline != null && deadline > System.nanoTime();
  }

  private AppException invalidPlan() {
    return new AppException(HttpStatus.BAD_GATEWAY, "INVALID_TRAVEL_PLAN", "模型未返回完整有效的行程，请重试");
  }

  private String buildTravelPrompt(String city, java.math.BigDecimal budget, Integer days) {
    return "你是一个专业的旅游规划师，擅长根据用户的需求生成详细的旅行行程。\n\n"
        + "请根据以下信息为用户生成一份详细的旅游规划：\n"
        + "- 目的地城市："
        + city
        + "\n"
        + "- 预算："
        + budget
        + "元\n"
        + "- 旅行天数："
        + days
        + "天\n\n"
        + "要求：\n"
        + "1. 每天的行程安排（上午、下午、晚上）\n"
        + "2. 每个景点的详细介绍\n"
        + "3. 交通建议\n"
        + "4. 预算分配明细\n"
        + "5. 注意事项；交通、门票及费用为参考估算，不声称查询过实时数据。\n"
        + "6. 必须生成完整的"
        + days
        + "天，day从1连续编号；城市与总预算必须与输入一致。\n\n"
        + (days > 7 ? "长行程请压缩文字：每时段介绍不超过20个汉字，交通与费用只写简短估算，保证所有天数完整。\n\n" : "")
        + "请以JSON格式输出，结构如下：\n"
        + "{\n"
        + "  \"success\": true,\n"
        + "  \"city\": \"城市名\",\n"
        + "  \"days\": 天数,\n"
        + "  \"totalBudget\": 总预算,\n"
        + "  \"dailyItinerary\": [\n"
        + "    {\n"
        + "      \"day\": 1,\n"
        + "      \"date\": \"第1天\",\n"
        + "      \"morning\": {\n"
        + "        \"spot\": \"景点名称\",\n"
        + "        \"duration\": \"游览时长\",\n"
        + "        \"ticket\": \"门票价格\",\n"
        + "        \"transportation\": \"交通方式\",\n"
        + "        \"description\": \"景点介绍\"\n"
        + "      },\n"
        + "      \"afternoon\": {\n"
        + "        \"spot\": \"景点名称\",\n"
        + "        \"duration\": \"游览时长\",\n"
        + "        \"ticket\": \"门票价格\",\n"
        + "        \"transportation\": \"交通方式\",\n"
        + "        \"description\": \"景点介绍\"\n"
        + "      },\n"
        + "      \"evening\": {\n"
        + "        \"spot\": \"活动名称\",\n"
        + "        \"duration\": \"活动时长\",\n"
        + "        \"ticket\": \"费用\",\n"
        + "        \"transportation\": \"交通方式\",\n"
        + "        \"description\": \"活动介绍\"\n"
        + "      }\n"
        + "    }\n"
        + "  ],\n"
        + "  \"budgetBreakdown\": {\n"
        + "    \"accommodation\": 住宿费用,\n"
        + "    \"food\": 餐饮费用,\n"
        + "    \"transportation\": 交通费用,\n"
        + "    \"tickets\": 门票费用,\n"
        + "    \"other\": 其他费用\n"
        + "  },\n"
        + "  \"tips\": [\"提示1\", \"提示2\", \"提示3\"],\n"
        + "  \"warnings\": [\"注意事项1\", \"注意事项2\"]\n"
        + "}\n\n"
        + "请确保JSON格式正确，可以被解析。";
  }

  public SseEmitter chat(long owner, String conversationId, String requestId, String message) {
    return chat(owner, conversationId, requestId, message, null);
  }

  public SseEmitter chat(long owner, String conversationId, String requestId, String message,
      String city) {
    for (Session pending : active.values())
      if (pending.owner == owner && pending.ended.get() && pending.turn != null
          && pending.turn.conversationId().equals(conversationId)) persist(pending, true);
    if (!acceptedChats.tryAcquire())
      throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_BUSY", "当前请求较多或消息待保存，请稍后重试");
    Session session = new Session(owner, requestId, new SseEmitter(timeoutMs + 5000));
    session.future =
        new FutureTask<>(
            () -> {
              run(session, message);
              return null;
            });
    if (active.putIfAbsent(session.key, session) != null) {
      acceptedChats.release();
      throw new AppException(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "该请求正在执行，请勿重复提交");
    }
    try {
      synchronized (session) {
        if (session.ended.get() || wasCancelled("chat", session.key))
          throw new AppException(HttpStatus.CONFLICT, "CHAT_CANCELLED", "该聊天请求已取消");
        session.turn = data.begin(owner, conversationId, requestId, message, city);
      }
    } catch (RuntimeException e) {
      release(session);
      throw e;
    }
    session.emitter.onCompletion(() -> finish(session, null, true));
    session.emitter.onError(error -> finish(session, null, true));
    session.emitter.onTimeout(
        () ->
            fail(
                session,
                new AppException(HttpStatus.GATEWAY_TIMEOUT, "STREAM_TIMEOUT", "生成超时，请重试")));
    try {
      synchronized (session) {
        if (!session.ended.get()) workers.execute(session.future);
      }
    } catch (RejectedExecutionException e) {
      fail(
          session,
          new AppException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_BUSY", "当前请求较多，请稍后重试"));
      throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_BUSY", "当前请求较多，请稍后重试");
    }
    return session.emitter;
  }

  private void run(Session session, String message) {
    session.runner = Thread.currentThread();
    try (var trace =
        org.example.traveljavaserver.utils.ModelTrace.open(
            session.id, org.example.traveljavaserver.utils.ModelTrace.CHAT_PROMPT, null)) {
      if (!send(session, StreamEvent.start(session.id))) return;
      Call call = llm.newChatCall(session.turn.messages(), true);
      synchronized (session) {
        session.call = call;
        if (session.ended.get()) {
          call.cancel();
          return;
        }
        if (wasCancelled("chat", session.key)) {
          finish(session, StreamEvent.done(session.id), true);
          return;
        }
      }
      llm.readStream(call, content -> append(session, content));
      finish(session, StreamEvent.done(session.id), false);
    } catch (CancellationException e) {
      finish(session, null, true);
    } catch (AppException e) {
      fail(session, e);
    } catch (Exception e) {
      fail(
          session,
          new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "生成失败，请稍后重试"));
    }
  }

  private void append(Session session, String content) {
    String checkpoint = null;
    synchronized (session) {
      if (session.ended.get()) throw new CancellationException();
      if (session.answer.length() + content.length() > 32000)
        throw new AppException(HttpStatus.BAD_GATEWAY, "MODEL_OUTPUT_TOO_LARGE", "回答过长，请缩短问题后重试");
      session.answer.append(content);
      if (!session.checkpointDisabled
          && System.nanoTime() - session.lastCheckpoint >= TimeUnit.MILLISECONDS.toNanos(500)) {
        checkpoint = session.answer.toString(); session.lastCheckpoint = System.nanoTime();
      }
    }
    if (!send(session, StreamEvent.chunk(session.id, content))) throw new CancellationException();
    if (checkpoint != null) {
      try { data.checkpoint(session.turn, checkpoint); }
      catch (RuntimeException error) {
        session.checkpointDisabled = true;
        log.warn("Chat checkpoint disabled requestId={}", session.id);
      }
    }
  }

  public boolean cancel(long owner, String requestId) {
    String key = owner + ":" + requestId;
    Session session = active.get(key);
    if (session == null) {
      if (!rememberCancellation("chat", key)) return false;
      session = active.get(key);
      if (session == null) return true;
    }
    rememberCancellation("chat", key);
    synchronized (session) {
      return session.ended.get() ? persist(session, true)
          : finish(session, StreamEvent.done(session.id), true);
    }
  }

  public void cancelUser(long owner) {
    planningCalls.values().stream().filter(run -> run.owner() == owner)
        .forEach(run -> run.call().cancel());
    for (Session session : active.values())
      if (session.owner == owner) cancel(owner, session.id);
  }

  public void cancelConversation(long owner, String conversationId) {
    data.conversation(owner, conversationId);
    for (Session session : active.values())
      if (session.owner == owner
          && session.turn != null
          && session.turn.conversationId().equals(conversationId))
        cancel(owner, session.id);
  }

  private boolean send(Session session, StreamEvent event) {
    synchronized (session) {
      if (session.ended.get()) return false;
      try {
        session.emitter.send(SseEmitter.event().data(mapper.writeValueAsString(event)));
        return true;
      } catch (IOException | IllegalStateException e) {
        finish(session, null, true);
        return false;
      }
    }
  }

  private void fail(Session session, AppException error) {
    finish(session, StreamEvent.error(session.id, error.getErrorCode(), error.getMessage()), true);
  }

  private boolean finish(Session session, StreamEvent terminal, boolean cancelWork) {
    synchronized (session) {
      if (!session.ended.compareAndSet(false, true)) return false;
      if (cancelWork) {
        if (session.call != null) session.call.cancel();
        if (session.future != null) {
          session.future.cancel(Thread.currentThread() != session.runner);
          workers.remove(session.future);
        }
      }
      if (session.turn != null) {
        session.finalStatus =
            terminal == null || cancelWork && "done".equals(terminal.type())
                ? "stopped"
                : "error".equals(terminal.type()) ? "error" : "completed";
        session.finalCode = terminal == null ? null : terminal.code();
        session.finalMessage = terminal == null ? null : terminal.message();
        if (!persist(session, true))
          terminal = StreamEvent.error(session.id, "PERSISTENCE_ERROR", "消息暂未保存，系统将重试；请稍后刷新或重试取消");
      } else {
        release(session);
      }
      log.info(
          "chat_end requestId={} outcome={} upstreamCancelled={} active={}",
          session.id,
          terminal == null
              ? "disconnected"
              : terminal.code() == null ? terminal.type() : terminal.code(),
          session.call != null && session.call.isCanceled(),
          active.size());
      try {
        if (terminal != null)
          session.emitter.send(SseEmitter.event().data(mapper.writeValueAsString(terminal)));
        session.emitter.complete();
      } catch (IOException | IllegalStateException ignored) {
        // A disconnected response cannot receive an additional error frame.
      }
      return session.persisted || session.turn == null;
    }
  }

  private void maintain() {
    cancelledRequests.entrySet().removeIf(e -> e.getValue() < System.nanoTime());
    for (Session session : active.values()) {
      try {
        if (session.ended.get()) { persist(session, false); continue; }
        if (System.nanoTime() - session.startedAt >= TimeUnit.MILLISECONDS.toNanos(timeoutMs)) {
          fail(session, new AppException(HttpStatus.GATEWAY_TIMEOUT, "STREAM_TIMEOUT", "生成超时，请重试"));
          continue;
        }
        synchronized (session) {
          if (!session.ended.get()) session.emitter.send(SseEmitter.event().comment("keepalive"));
        }
      } catch (IOException | IllegalStateException e) {
        finish(session, null, true);
      }
    }
  }

  /** At most five automatic attempts; explicit cancel/new turn may retry after DB recovery. */
  private boolean persist(Session session, boolean force) {
    synchronized (session) {
      if (!session.ended.get()) return false;
      if (session.persisted) return true;
      if (!force && (session.saveAttempts >= 5 || System.nanoTime() < session.retryAt)) return false;
      try {
        session.saveAttempts++;
        data.finish(session.turn, session.answer.toString(), session.finalStatus,
            session.finalCode, session.finalMessage);
        session.persisted = true;
        release(session);
        return true;
      } catch (RuntimeException error) {
        session.retryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(
            Math.min(16, 1L << Math.min(4, session.saveAttempts - 1)));
        log.warn("Chat save pending requestId={} attempt={}", session.id, session.saveAttempts);
        return false;
      }
    }
  }

  private void release(Session session) {
    if (active.remove(session.key, session)) acceptedChats.release();
  }

  int activeStreamCount() {
    return active.size();
  }

  int queuedStreamCount() {
    return workers.getQueue().size();
  }

  int runningStreamCount() {
    return workers.getActiveCount();
  }

  @PreDestroy
  void close() {
    planningCalls.values().forEach(run -> run.call().cancel());
    for (Session session : active.values()) {
      if (session.ended.get()) persist(session, true);
      else finish(session, null, true);
    }
    maintenance.shutdownNow();
    workers.shutdownNow();
  }

  private static final class Session {
    final String id;
    final long owner;
    final String key;
    DataService.Turn turn;
    final StringBuilder answer = new StringBuilder();
    long lastCheckpoint;
    volatile boolean checkpointDisabled;
    boolean persisted;
    int saveAttempts;
    long retryAt;
    String finalStatus, finalCode, finalMessage;
    final SseEmitter emitter;
    final long startedAt = System.nanoTime();
    final AtomicBoolean ended = new AtomicBoolean();
    volatile Call call;
    volatile Thread runner;
    FutureTask<Void> future;

    Session(long owner, String id, SseEmitter emitter) {
      this.owner = owner;
      this.id = id;
      this.key = owner + ":" + id;
      this.emitter = emitter;
    }
  }
}

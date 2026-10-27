package org.example.traveljavaserver.agent;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.example.traveljavaserver.common.AppException;
import org.example.traveljavaserver.dto.DataRequests;
import org.example.traveljavaserver.service.DataService;
import org.example.traveljavaserver.service.PlanStructure;
import org.example.traveljavaserver.vo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AgentJobs {
  private static final Set<String> ACTIVE = Set.of("QUEUED", "RUNNING");
  private final AgentEngine engine;
  private final DataService data;
  private final long timeoutMs;
  private final Map<String, AgentRun> jobs = new ConcurrentHashMap<>();
  private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(8), task -> { var t = new Thread(task, "travel-agent"); t.setDaemon(true); return t; },
      new ThreadPoolExecutor.AbortPolicy());
  private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(task -> {
    var t = new Thread(task, "travel-agent-clock"); t.setDaemon(true); return t;
  });
  public AgentJobs(AgentEngine engine, DataService data, @Value("${agent.timeout-ms:240000}") long timeoutMs) {
    this.engine = engine; this.data = data; this.timeoutMs = timeoutMs;
    clock.scheduleAtFixedRate(this::maintain, 1, 1, TimeUnit.SECONDS);
  }
  public Map<String, Object> capabilities() {
    return Map.of("mapsConfigured", engine.mapsConfigured(), "maxDays", 7, "draftRetentionMinutes", 30,
        "budgetScope", AgentRules.SCOPE, "travelModes", TravelModes.ALL);
  }
  public synchronized AgentTypes.View create(long owner, AgentTypes.Start request) {
    String fingerprint = AgentRules.JSON.valueToTree(request).toString();
    var existing = duplicate(owner, request.requestId(), fingerprint);
    if (existing != null) return view(existing);
    return submit(new AgentRun(owner, request, null, null, null, fingerprint, timeoutMs));
  }
  public synchronized AgentTypes.View revise(long owner, String tripId, AgentTypes.Revise request) {
    String fingerprint = tripId + ":" + AgentRules.JSON.valueToTree(request);
    var existing = duplicate(owner, request.requestId(), fingerprint);
    if (existing != null) return view(existing);
    var base = data.trip(owner, tripId, null);
    if (request.baseVersion() != base.trip().currentVersion()) throw conflict();
    if (request.day() > base.plan().getDays() || !PlanStructure.complete(base.plan()))
      throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请选择行程中存在的日期");
    var start = new AgentTypes.Start(request.requestId(), base.plan().getCity(), base.plan().getDays(),
        base.plan().getTotalBudget(), request.instruction(), TravelModes.normalize(base.plan().getTravelMode()));
    return submit(new AgentRun(owner, start, request, tripId, AgentRules.copy(base.plan()), fingerprint, timeoutMs));
  }
  private AgentRun duplicate(long owner, String requestId, String fingerprint) {
    maintain();
    for (var run : jobs.values()) if (run.owner == owner && run.requestId().equals(requestId)) {
      if (!run.fingerprint.equals(fingerprint))
        throw new AppException(HttpStatus.CONFLICT, "REQUEST_CONFLICT", "该请求标识已用于其他参数，请重新发起");
      return run;
    }
    return null;
  }
  private AgentTypes.View submit(AgentRun run) {
    long own = jobs.values().stream().filter(j -> j.owner == run.owner && ACTIVE.contains(j.status)).count();
    if (own >= 1 || jobs.size() >= 200)
      throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "AGENT_BUSY", "已有智能规划正在进行，请等待或取消后重试");
    jobs.put(run.id, run);
    run.future = new FutureTask<>(() -> {
      try {
        synchronized (run) { run.remaining(); run.status = "RUNNING"; }
        var result = engine.execute(run);
        synchronized (run) {
          run.remaining(); run.plan = result;
          run.changes = AgentRules.diff(run.base, result);
          run.progress = "校验通过，请预览后确认保存"; run.status = "READY";
        }
      } catch (Exception e) {
        String code = "AGENT_FAILED", message = "智能规划未完成，请重试；已有行程未修改";
        AgentTypes.Refusal refusal = null;
        for (Throwable c = e; c != null; c = c.getCause()) {
          if (c instanceof AgentDeclined declined) {
            refusal = declined.refusal; code = refusal.code(); message = refusal.reason(); break;
          }
          if (c instanceof AppException app) { code = app.getErrorCode(); message = app.getMessage(); break; }
          if (c instanceof CancellationException) { code = "AGENT_CANCELLED"; message = "已取消"; break; }
        }
        synchronized (run) {
          if (ACTIVE.contains(run.status)) {
            run.status = refusal != null ? "REFUSED" : code.equals("AGENT_CANCELLED") ? "CANCELLED" : "ERROR";
            run.refusal = refusal; run.plan = null;
            if (refusal != null) run.report = new AgentTypes.Report(false, List.of(refusal.reason()), List.of(AgentRules.SCOPE), null, null);
            run.errorCode = refusal == null ? code : null; run.message = message; run.progress = message;
          }
        }
      } finally { run.calls.forEach(okhttp3.Call::cancel); run.calls.clear(); }
      return null;
    });
    try { workers.execute(run.future); }
    catch (RejectedExecutionException e) {
      jobs.remove(run.id);
      throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_BUSY", "智能规划队列已满，请稍后重试");
    }
    return view(run);
  }
  private AgentRun owned(long owner, String id) {
    var run = jobs.get(id);
    if (run == null || run.owner != owner)
      throw new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "智能规划任务不存在或已过期，请重新生成");
    return run;
  }
  public AgentTypes.View get(long owner, String id) { return view(owned(owner, id)); }
  public DataViews.TripDetail save(long owner, String id, AgentTypes.Save request) {
    var run = owned(owner, id);
    synchronized (run) {
      if (run.status.equals("SAVED")) return data.trip(owner, run.savedTripId, run.savedVersion);
      if (!run.status.equals("READY")) throw new AppException(HttpStatus.CONFLICT, "DRAFT_NOT_READY", "草稿尚未就绪或已取消");
      String title = request.title();
      if ((title == null || title.isBlank()) && run.tripId != null) title = data.trip(owner, run.tripId, null).trip().title();
      var result = data.saveAgent(owner, run.tripId, new DataRequests.SaveTrip(title, run.plan,
          run.revision == null ? null : run.revision.baseVersion()));
      run.savedTripId = result.trip().id(); run.savedVersion = result.trip().currentVersion();
      run.status = "SAVED"; run.progress = "已保存";
      return result;
    }
  }
  public boolean cancel(long owner, String id) { return cancel(owned(owner, id)); }
  private boolean cancel(AgentRun run) {
    synchronized (run) {
      if (Set.of("SAVED", "ERROR", "CANCELLED", "REFUSED").contains(run.status)) return false;
      run.status = "CANCELLED"; run.progress = "已取消，已有行程未修改"; run.cancel(); workers.remove(run.future);
      return true;
    }
  }
  public void cancelUser(long owner) { jobs.values().stream().filter(r -> r.owner == owner).forEach(this::cancel); }
  private AgentTypes.View view(AgentRun r) {
    synchronized (r) {
      return new AgentTypes.View(r.id, r.status, r.progress, r.errorCode, r.message,
          r.savedTripId == null ? r.tripId : r.savedTripId, r.revision == null ? null : r.revision.baseVersion(),
          Set.of("READY", "SAVED").contains(r.status) ? r.plan : null, r.changes, List.copyOf(r.traces),
          r.savedVersion, r.report, r.modelRounds, r.toolCalls, r.start.travelMode(), r.refusal);
    }
  }
  private void maintain() {
    for (var r : jobs.values()) {
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - r.created);
      synchronized (r) {
        if (ACTIVE.contains(r.status) && elapsed > timeoutMs) {
          r.status = "ERROR"; r.errorCode = "AGENT_TIMEOUT"; r.message = "智能规划超时，已有行程未修改";
          r.progress = r.message; r.cancel(); workers.remove(r.future);
        }
        if (!ACTIVE.contains(r.status) && elapsed > TimeUnit.MINUTES.toMillis(30)) jobs.remove(r.id, r);
      }
    }
  }
  private AppException conflict() { return new AppException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "行程版本已变化，请刷新后重新生成修改草稿"); }
  @PreDestroy public void close() {
    jobs.values().forEach(AgentRun::cancel); workers.shutdownNow(); clock.shutdownNow();
  }
}


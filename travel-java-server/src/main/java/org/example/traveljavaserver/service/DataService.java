package org.example.traveljavaserver.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.example.traveljavaserver.common.AppException;
import org.example.traveljavaserver.dto.DataRequests;
import org.example.traveljavaserver.persistence.*;
import org.example.traveljavaserver.vo.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DataService {
  private final StoreMapper store;
  private final ChatContext context;
  private final com.fasterxml.jackson.databind.ObjectMapper mapper =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private AppException missing() {
    return new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "记录不存在或无权访问");
  }

  private void paging(int page, int size) {
    if (page < 1 || page > 100000 || size < 1 || size > 50)
      throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "分页参数不正确");
  }

  private void validate(TravelRecommendVO plan) {
    if (plan == null
        || !Boolean.TRUE.equals(plan.getSuccess())
        || plan.getCity() == null
        || plan.getCity().isBlank()
        || plan.getCity().length() > 50
        || plan.getDays() == null
        || plan.getDays() < 1
        || plan.getDays() > 30
        || plan.getTotalBudget() == null
        || plan.getTotalBudget().compareTo(new java.math.BigDecimal("100")) < 0
        || plan.getTotalBudget().compareTo(new java.math.BigDecimal("1000000")) > 0
        || !PlanStructure.complete(plan))
      throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "行程内容不完整或参数超出范围");
    try {
      plan.setTotalBudget(plan.getTotalBudget().setScale(2, java.math.RoundingMode.UNNECESSARY));
    } catch (ArithmeticException e) {
      throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "预算最多保留两位小数");
    }
    if (context.encode(plan).length() > 200000)
      throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "行程内容过长");
  }

  @Transactional
  public DataViews.TripDetail save(long owner, String id, DataRequests.SaveTrip request) {
    if (request.plan() != null && request.plan().getEvidence() != null)
      throw new AppException(
          HttpStatus.BAD_REQUEST, "USE_PLANNING_JOB_SAVE", "带查询凭证的行程必须从服务端规划草稿保存");
    return persist(owner, id, request);
  }

  @Transactional
  public DataViews.TripDetail saveVerified(long owner, String id, DataRequests.SaveTrip request) {
    org.example.traveljavaserver.planning.PlanRules.calculate(request.plan());
    return persist(owner, id, request);
  }

  private DataViews.TripDetail persist(long owner, String id, DataRequests.SaveTrip request) {
    validate(request.plan());
    Rows.Trip trip;
    if (id == null) {
      trip = new Rows.Trip();
      trip.setId(UUID.randomUUID().toString());
      trip.setUserId(owner);
      trip.setCurrentVersion(1);
    } else {
      trip = store.lockTrip(owner, id);
      if (trip == null) throw missing();
      if (!Objects.equals(request.baseVersion(), trip.getCurrentVersion()))
        throw new AppException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "行程已更新，请刷新后重试");
      trip.setCurrentVersion(trip.getCurrentVersion() + 1);
    }
    String title =
        request.title() == null || request.title().isBlank()
            ? request.plan().getCity() + " · " + request.plan().getDays() + "天行程"
            : request.title().trim();
    trip.setTitle(title);
    trip.setCity(request.plan().getCity().trim());
    trip.setDays(request.plan().getDays());
    trip.setBudget(request.plan().getTotalBudget());
    if (id == null) store.addTrip(trip);
    else store.updateTrip(trip);
    Rows.Version version = new Rows.Version();
    version.setId(UUID.randomUUID().toString());
    version.setTripId(trip.getId());
    version.setVersion(trip.getCurrentVersion());
    version.setPlanJson(context.encode(request.plan()));
    store.addVersion(version);
    return trip(owner, trip.getId(), null);
  }

  @Transactional(readOnly = true)
  public DataViews.TripDetail trip(long owner, String id, Integer selectedVersion) {
    Rows.Trip trip = store.trip(owner, id);
    if (trip == null) throw missing();
    Rows.Version version =
        store.version(id, selectedVersion == null ? trip.getCurrentVersion() : selectedVersion);
    if (version == null) throw missing();
    try {
      return new DataViews.TripDetail(
          DataViews.Trip.of(trip),
          mapper.readValue(version.getPlanJson(), TravelRecommendVO.class),
          store.versions(id).stream()
              .map(v -> new DataViews.Version(v.getVersion(), v.getCreatedAt()))
              .toList());
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Invalid stored plan", e);
    }
  }

  @Transactional(readOnly = true)
  public DataViews.Page<DataViews.Trip> trips(long owner, int page, int size) {
    paging(page, size);
    return new DataViews.Page<>(
        store.trips(owner, size, (page - 1) * size).stream().map(DataViews.Trip::of).toList(),
        store.tripCount(owner),
        page,
        size);
  }

  @Transactional
  public void deleteTrip(long owner, String id) {
    if (store.deleteTrip(owner, id) != 1) throw missing();
  }

  @Transactional
  public DataViews.Conversation createConversation(
      long owner, DataRequests.NewConversation request) {
    Map<String, Object> state = new LinkedHashMap<>();
    if (request.city() != null && !request.city().isBlank())
      state.put("city", request.city().trim());
    if (request.days() != null) state.put("days", request.days());
    if (request.budget() != null) state.put("budget", request.budget());
    if (request.tripId() != null) context.refresh(state, trip(owner, request.tripId(), null));
    Rows.Conversation row = new Rows.Conversation();
    row.setId(UUID.randomUUID().toString());
    row.setUserId(owner);
    row.setTripId(request.tripId());
    row.setTitle(
        request.title() == null || request.title().isBlank()
            ? request.tripId() == null ? "新对话" : "行程咨询"
            : request.title().trim());
    row.setContextJson(context.encode(state));
    store.addConversation(row);
    return conversation(owner, row.getId());
  }

  @Transactional(readOnly = true)
  public DataViews.Conversation conversation(long owner, String id) {
    Rows.Conversation row = store.conversation(owner, id);
    if (row == null) throw missing();
    return view(row);
  }

  private DataViews.Conversation view(Rows.Conversation row) {
    return new DataViews.Conversation(
        row.getId(),
        row.getTripId(),
        row.getTitle(),
        context.publicState(context.decode(row.getContextJson())),
        row.getActiveRequestId(),
        row.getCreatedAt(),
        row.getUpdatedAt());
  }

  @Transactional(readOnly = true)
  public DataViews.Page<DataViews.Conversation> conversations(long owner, int page, int size) {
    paging(page, size);
    return new DataViews.Page<>(
        store.conversations(owner, size, (page - 1) * size).stream().map(this::view).toList(),
        store.conversationCount(owner),
        page,
        size);
  }

  @Transactional
  public void deleteConversation(long owner, String id) {
    Rows.Conversation row = store.lockConversation(owner, id);
    if (row == null) throw missing();
    if (row.getActiveRequestId() != null)
      throw new AppException(HttpStatus.CONFLICT, "CONVERSATION_BUSY", "请先停止当前生成，再删除会话");
    store.deleteConversation(owner, id);
  }

  @Transactional(readOnly = true)
  public DataViews.MessagePage messages(long owner, String id, Long before, int size) {
    conversation(owner, id);
    if (size < 1 || size > 100 || before != null && before < 1)
      throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "消息分页参数不正确");
    List<Rows.Message> rows = new ArrayList<>(store.messages(owner, id, before, size + 1));
    boolean more = rows.size() > size;
    if (more) rows.remove(rows.size() - 1);
    Long next = rows.isEmpty() ? null : rows.get(rows.size() - 1).getSeq();
    Collections.reverse(rows);
    return new DataViews.MessagePage(rows.stream().map(DataViews.Message::of).toList(), next, more);
  }

  public record Turn(
      long owner,
      String conversationId,
      String requestId,
      String assistantId,
      List<Map<String, String>> messages,
      String city) {}

  @Transactional
  public Turn begin(long owner, String conversationId, String requestId, String message) {
    return begin(owner, conversationId, requestId, message, null);
  }

  @Transactional
  public Turn begin(long owner, String conversationId, String requestId, String message, String city) {
    Rows.Conversation row = store.lockConversation(owner, conversationId);
    if (row == null) throw missing();
    if (store.requestExists(owner, requestId) > 0)
      throw new AppException(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "该请求已提交，请勿重复发送");
    if (row.getActiveRequestId() != null)
      throw new AppException(HttpStatus.CONFLICT, "CONVERSATION_BUSY", "该会话正在生成，请先停止或等待完成");
    Map<String, Object> state = context.decode(row.getContextJson());
    if (row.getTripId() != null) context.refresh(state, trip(owner, row.getTripId(), null));
    if (city != null && !city.isBlank()) {
      state.put("city", org.example.traveljavaserver.knowledge.CityCatalog.canonical(city));
      Set<Object> overrides = new LinkedHashSet<>((List<?>) state.getOrDefault("_overrides", List.of()));
      overrides.add("city");
      state.put("_overrides", new ArrayList<>(overrides));
    }
    List<Map<String, String>> modelMessages =
        context.messages(state, store.context(owner, conversationId), message);
    Rows.Message user = message(owner, conversationId, requestId, "user", message, "completed");
    Rows.Message assistant =
        message(owner, conversationId, requestId, "assistant", "", "streaming");
    try {
      store.addMessage(user);
      store.addMessage(assistant);
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw new AppException(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "该请求已提交，请勿重复发送");
    }
    row.setContextJson(context.encode(state));
    row.setActiveRequestId(requestId);
    if (row.getTitle().equals("新对话"))
      row.setTitle(message.substring(0, Math.min(40, message.length())));
    store.updateConversation(row);
    return new Turn(owner, conversationId, requestId, assistant.getId(), modelMessages,
        String.valueOf(state.getOrDefault("city", "")));
  }

  private Rows.Message message(
      long owner,
      String conversationId,
      String requestId,
      String role,
      String content,
      String status) {
    Rows.Message row = new Rows.Message();
    row.setId(UUID.randomUUID().toString());
    row.setUserId(owner);
    row.setConversationId(conversationId);
    row.setRequestId(requestId);
    row.setRole(role);
    row.setContent(content);
    row.setStatus(status);
    return row;
  }

  public void checkpoint(Turn turn, String content) {
    store.checkpoint(turn.owner(), turn.assistantId(), content);
  }

  public void evidence(Turn turn, org.example.traveljavaserver.knowledge.ChatEvidence evidence) {
    store.messageEvidence(turn.owner(), turn.assistantId(), context.encode(evidence));
  }

  @Transactional
  public void finish(Turn turn, String content, String status, String code, String message) {
    store.finishMessage(turn.owner(), turn.assistantId(), content, status, code, message);
    store.releaseConversation(turn.owner(), turn.conversationId(), turn.requestId());
  }

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void recover() {
    store.recoverMessages();
    store.recoverConversations();
  }
}

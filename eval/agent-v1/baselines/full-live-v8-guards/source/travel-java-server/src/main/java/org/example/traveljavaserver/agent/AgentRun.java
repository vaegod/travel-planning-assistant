package org.example.traveljavaserver.agent;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import org.example.traveljavaserver.common.AppException;
import org.example.traveljavaserver.planning.PlanTypes;
import org.example.traveljavaserver.utils.ModelTrace;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.springframework.http.HttpStatus;

final class AgentRun {
  final String id = UUID.randomUUID().toString();
  final long owner, created = System.nanoTime(), timeoutMs;
  final AgentTypes.Start start;
  final AgentTypes.Revise revision;
  final String tripId, fingerprint;
  final TravelRecommendVO base;
  final AtomicBoolean cancelled = new AtomicBoolean();
  final Set<Call> calls = ConcurrentHashMap.newKeySet();
  final List<PlanTypes.Trace> traces = new CopyOnWriteArrayList<>();
  final List<ModelTrace.Entry> modelCalls = new CopyOnWriteArrayList<>();
  volatile String status = "QUEUED", progress = "等待开始", errorCode, message, savedTripId;
  volatile Integer savedVersion;
  volatile TravelRecommendVO plan;
  volatile AgentTypes.Report report;
  volatile AgentTypes.Refusal refusal;
  volatile boolean draftAccepted;
  boolean routesRequired;
  final Map<String, AgentMaps.Route> verifiedRoutes = new HashMap<>();
  final Set<String> requiredRoutes = new HashSet<>();
  volatile List<PlanTypes.Change> changes = List.of();
  volatile FutureTask<Void> future;
  volatile int modelRounds, toolCalls;
  int mapRequests;
  AgentRun(long owner, AgentTypes.Start start, AgentTypes.Revise revision, String tripId,
      TravelRecommendVO base, String fingerprint, long timeoutMs) {
    this.owner = owner; this.start = start; this.revision = revision; this.tripId = tripId;
    this.base = base; this.fingerprint = fingerprint; this.timeoutMs = timeoutMs;
  }
  String requestId() { return revision == null ? start.requestId() : revision.requestId(); }
  long remaining() {
    if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    long left = timeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - created);
    if (left <= 0) throw error("AGENT_TIMEOUT", "智能规划超过总时限，请减少要求后重试");
    return left;
  }
  void track(Call call) {
    remaining(); calls.add(call);
    if (cancelled.get()) { call.cancel(); calls.remove(call); throw new CancellationException(); }
  }
  void cancel() {
    cancelled.set(true); calls.forEach(Call::cancel);
    if (future != null) future.cancel(true);
  }
  void trace(String name, String status, long at, String detail) {
    traces.add(new PlanTypes.Trace(name, status,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - at), detail, Instant.now().toString()));
  }
  static AppException error(String code, String message) {
    return new AppException(HttpStatus.BAD_GATEWAY, code, message);
  }
}


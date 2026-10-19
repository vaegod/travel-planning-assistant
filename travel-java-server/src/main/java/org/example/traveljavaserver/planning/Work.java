package org.example.traveljavaserver.planning;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import org.example.traveljavaserver.common.AppException;
import org.springframework.http.HttpStatus;

final class Work {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Work.class);
  static final ThreadLocal<Work> CURRENT = new ThreadLocal<>();
  final String id = UUID.randomUUID().toString();
  final long owner;
  final PlanTypes.Start start;
  final String tripId;
  final PlanTypes.Revise revision;
  final long created = System.nanoTime();
  final long timeoutMs;
  final AtomicBoolean cancelled = new AtomicBoolean();
  final Set<Call> calls = ConcurrentHashMap.newKeySet();
  final List<PlanTypes.Trace> traces = new CopyOnWriteArrayList<>();
  final List<org.example.traveljavaserver.utils.ModelTrace.Entry> modelCalls =
      new CopyOnWriteArrayList<>();
  volatile String status = "QUEUED", progress = "等待开始", errorCode, message;
  volatile org.example.traveljavaserver.vo.TravelRecommendVO plan;
  volatile List<PlanTypes.Change> changes = List.of();
  volatile Integer savedVersion;
  volatile String savedTripId;
  volatile FutureTask<Void> future;
  int used;

  Work(
      long owner, PlanTypes.Start start, String tripId, PlanTypes.Revise revision, long timeoutMs) {
    this.owner = owner;
    this.start = start;
    this.tripId = tripId;
    this.revision = revision;
    this.timeoutMs = timeoutMs;
  }

  long remaining() {
    if (cancelled.get() || Thread.currentThread().isInterrupted())
      throw new CancellationException();
    long left = timeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - created);
    if (left <= 0) throw error("PLANNING_TIMEOUT", "规划超时，请重试");
    return left;
  }

  void use() {
    remaining();
    if (++used > 8) throw error("TOOL_LIMIT", "本次任务已达到调用上限");
  }

  void track(Call call) {
    remaining();
    calls.add(call);
    if (cancelled.get()) {
      call.cancel();
      throw new CancellationException();
    }
  }

  void trace(String tool, String status, long started, String detail) {
    LOG.info(
        "planning_step jobId={} tool={} status={} durationMs={}",
        id,
        tool,
        status,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    traces.add(
        new PlanTypes.Trace(
            tool,
            status,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            detail,
            Instant.now().toString()));
  }

  static Work current() {
    Work work = CURRENT.get();
    if (work == null) throw new IllegalStateException("Planning scope is required");
    return work;
  }

  static AppException error(String code, String message) {
    return new AppException(HttpStatus.BAD_GATEWAY, code, message);
  }

  void cancel() {
    cancelled.set(true);
    calls.forEach(Call::cancel);
    if (future != null) future.cancel(true);
  }
}

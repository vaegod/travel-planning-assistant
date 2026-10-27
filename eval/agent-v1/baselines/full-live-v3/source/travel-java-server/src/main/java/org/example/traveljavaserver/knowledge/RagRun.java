package org.example.traveljavaserver.knowledge;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import org.example.traveljavaserver.common.AppException;
import org.springframework.http.HttpStatus;

final class RagRun {
  static final ThreadLocal<RagRun> CURRENT = new ThreadLocal<>();
  final String id;
  final long owner, created = System.nanoTime(), timeoutMs;
  final AtomicBoolean cancelled = new AtomicBoolean();
  final Set<Call> calls = ConcurrentHashMap.newKeySet();
  volatile FutureTask<Void> future;
  int used;

  RagRun(String id, long owner, long timeoutMs) {
    this.id = id;
    this.owner = owner;
    this.timeoutMs = timeoutMs;
  }

  long remaining() {
    if (cancelled.get() || Thread.currentThread().isInterrupted())
      throw new CancellationException();
    long left = timeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - created);
    if (left <= 0) throw error("RAG_TIMEOUT", "资料任务超时，请重试");
    return left;
  }

  void track(Call call) {
    remaining();
    if (++used > 8) throw error("RAG_CALL_LIMIT", "资料任务达到调用上限");
    calls.add(call);
    if (cancelled.get()) {
      call.cancel();
      throw new CancellationException();
    }
  }

  void cancel() {
    cancelled.set(true);
    calls.forEach(Call::cancel);
    if (future != null) future.cancel(true);
  }

  static AppException error(String code, String text) {
    return new AppException(HttpStatus.BAD_GATEWAY, code, text);
  }

  static RagRun current() {
    var run = CURRENT.get();
    if (run == null) throw new IllegalStateException("RAG scope is missing");
    return run;
  }
}

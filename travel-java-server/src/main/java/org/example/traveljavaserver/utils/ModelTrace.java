package org.example.traveljavaserver.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.example.traveljavaserver.common.AppException;

/** Metadata only: never retains prompts, answers, credentials or HTTP headers. */
public final class ModelTrace {
  public static final String CHAT_PROMPT = "travel-chat-pure-v3";
  public static final String PLAN_PROMPT = "planning-select-v2";
  private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ModelTrace.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private ModelTrace() {}

  public record Entry(
      String requestId,
      String promptVersion,
      String model,
      String status,
      long durationMs,
      Long firstContentMs,
      Long inputTokens,
      Long outputTokens,
      Long cachedInputTokens,
      Long reasoningTokens,
      boolean usageComplete, String attemptId, int modelRound, String recoveryOf,
      String phase, String exceptionType, Boolean connectionReused,
      boolean requestSent, boolean responseStarted, Integer httpStatus) {}

  private record Context(String id, String version, Consumer<Entry> receive) {}

  public static Scope open(String id, String version, Consumer<Entry> receive) {
    Context previous = CURRENT.get();
    CURRENT.set(new Context(id, version, receive));
    return new Scope(previous);
  }

  public static final class Scope implements AutoCloseable {
    private final Context previous;

    private Scope(Context previous) {
      this.previous = previous;
    }

    public void close() {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }

  public static final class Attempt {
    private final Context context;
    private final String model;
    private final long start = System.nanoTime();
    private Long first, input, output, cached, reasoning;
    private boolean finished;
    final String id = UUID.randomUUID().toString();
    final String recoveryOf;
    int round;
    volatile String phase = "created", exceptionType;
    volatile Boolean reused;
    volatile boolean connecting, requestSent, responseStarted;
    volatile Integer httpStatus;
    volatile boolean recoverableIo;

    Attempt(String model) {
      this(model, null);
    }
    Attempt(String model, String recoveryOf) {
      this.model = model;
      this.recoveryOf = recoveryOf;
      Context current = CURRENT.get();
      this.context =
          current == null
              ? new Context(UUID.randomUUID().toString(), "legacy-recommend-v1", null)
              : current;
    }

    void content() {
      if (first == null) first = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
    void ioFailure(java.io.IOException error) {
      Throwable cause=error;
      for(int i=0;i<8&&cause.getCause()!=null&&cause.getCause()!=cause;i++)cause=cause.getCause();
      exceptionType=cause.getClass().getSimpleName();
      recoverableIo=java.util.Set.of("ConnectException","SocketException","EOFException",
          "SocketTimeoutException","InterruptedIOException","StreamResetException","ConnectionShutdownException").contains(exceptionType)
          ||cause instanceof java.net.ProtocolException&&cause.getMessage()!=null
              &&cause.getMessage().startsWith("unexpected end of stream");
    }

    void usage(JsonNode root) {
      var usage = root.path("usage");
      if (!usage.isObject()) return;
      input = number(usage, "prompt_tokens", input);
      output = number(usage, "completion_tokens", output);
      cached = number(usage.path("prompt_tokens_details"), "cached_tokens", cached);
      cached = number(usage, "prompt_cache_hit_tokens", cached);
      reasoning = number(usage.path("completion_tokens_details"), "reasoning_tokens", reasoning);
    }

    private Long number(JsonNode node, String key, Long fallback) {
      var value = node.path(key);
      if (value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0)
        return Long.valueOf(value.asLong());
      return fallback;
    }

    void finish(Throwable error) {
      if (finished) return;
      finished = true;
      String status =
          error == null
              ? "success"
              : error instanceof AppException app
                  ? app.getErrorCode()
                  : error instanceof java.util.concurrent.CancellationException
                      ? "cancelled"
                      : "failed";
      var entry =
          new Entry(
              context.id(),
              context.version(),
              model,
              status,
              TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
              first,
              input,
              output,
              cached,
              reasoning,
              error == null && input != null && output != null,
              id, round, recoveryOf, phase, exceptionType, reused, requestSent, responseStarted, httpStatus);
      try {
        LOG.info("model_call {}", JSON.writeValueAsString(entry));
        if (context.receive() != null) context.receive().accept(entry);
      } catch (Exception e) {
        LOG.warn("model_trace_failed requestId={}", context.id());
      }
    }
  }

  public static String describe(okhttp3.Call call, int round) {
    var attempt = call.request() == null ? null : call.request().tag(Attempt.class);
    if (attempt == null) return UUID.randomUUID().toString();
    attempt.round = round;
    return attempt.id;
  }
  public static boolean recoverable(okhttp3.Call call, String code) {
    var a=call.request()==null?null:call.request().tag(Attempt.class);
    if(a==null)return false;
    if(code.equals("MODEL_UNAVAILABLE"))return a.httpStatus!=null&&java.util.Set.of(502,503,504).contains(a.httpStatus);
    if(code.equals("MODEL_TIMEOUT"))return true;
    return code.equals("MODEL_CONNECTION_ERROR")&&a.recoverableIo;
  }
  static okhttp3.EventListener listener(okhttp3.Call call) {
    var a = call.request().tag(Attempt.class);
    if (a == null) return okhttp3.EventListener.NONE;
    return new okhttp3.EventListener() {
      @Override public void dnsStart(okhttp3.Call c, String domain) { a.phase="dns"; }
      @Override public void connectStart(okhttp3.Call c, java.net.InetSocketAddress address, java.net.Proxy proxy) {
        a.connecting=true; a.phase="connect";
      }
      @Override public void secureConnectStart(okhttp3.Call c) { a.phase="tls"; }
      @Override public void connectionAcquired(okhttp3.Call c, okhttp3.Connection connection) {
        a.reused=!a.connecting; a.phase="connection_acquired";
      }
      @Override public void requestHeadersStart(okhttp3.Call c) { a.phase="request_headers"; }
      @Override public void requestBodyStart(okhttp3.Call c) { a.phase="request_body"; }
      @Override public void requestBodyEnd(okhttp3.Call c, long bytes) {
        a.requestSent=true; a.phase="awaiting_response";
      }
      @Override public void responseHeadersEnd(okhttp3.Call c, okhttp3.Response response) {
        a.responseStarted=true; a.httpStatus=response.code(); a.phase="response_body";
      }
      @Override public void callFailed(okhttp3.Call c, java.io.IOException error) {
        // Class only: exception messages may contain hosts, credentials or request content.
        a.ioFailure(error);
      }
      @Override public void requestFailed(okhttp3.Call c,java.io.IOException error) { a.ioFailure(error); }
      @Override public void responseFailed(okhttp3.Call c,java.io.IOException error) { a.ioFailure(error); }
    };
  }
}

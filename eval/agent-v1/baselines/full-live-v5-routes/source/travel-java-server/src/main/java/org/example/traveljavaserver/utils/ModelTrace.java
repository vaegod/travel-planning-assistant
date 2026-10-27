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
      boolean usageComplete) {}

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

    Attempt(String model) {
      this.model = model;
      Context current = CURRENT.get();
      this.context =
          current == null
              ? new Context(UUID.randomUUID().toString(), "legacy-recommend-v1", null)
              : current;
    }

    void content() {
      if (first == null) first = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
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
              error == null && input != null && output != null);
      try {
        LOG.info("model_call {}", JSON.writeValueAsString(entry));
        if (context.receive() != null) context.receive().accept(entry);
      } catch (Exception e) {
        LOG.warn("model_trace_failed requestId={}", context.id());
      }
    }
  }
}

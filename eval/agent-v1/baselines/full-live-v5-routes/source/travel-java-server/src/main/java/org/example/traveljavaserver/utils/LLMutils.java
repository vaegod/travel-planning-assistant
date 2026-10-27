package org.example.traveljavaserver.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.*;
import org.example.traveljavaserver.common.AppException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LLMutils {
  private final String apiKey;
  private final String baseUrl;
  private final String model;
  private final Boolean enableThinking;
  private final int maxOutputTokens;
  private final OkHttpClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired
  public LLMutils(
      @Value("${llm.api-key}") String apiKey,
      @Value("${llm.base-url}") String baseUrl,
      @Value("${llm.model}") String model,
      @Value("${llm.timeout-seconds:120}") long timeoutSeconds,
      @Value("${llm.enable-thinking:}") String thinking,
      @Value("${llm.max-output-tokens:16384}") int maxOutputTokens) {
    this(
        apiKey,
        baseUrl,
        model,
        new OkHttpClient.Builder()
            .connectTimeout(Math.min(15, timeoutSeconds), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build(),
        parseThinking(thinking),
        maxOutputTokens);
  }

  public LLMutils(
      String apiKey, String baseUrl, String model, long timeoutSeconds, String thinking) {
    this(apiKey, baseUrl, model, timeoutSeconds, thinking, 16384);
  }

  public LLMutils(String apiKey, String baseUrl, String model, long timeoutSeconds) {
    this(apiKey, baseUrl, model, timeoutSeconds, "");
  }

  public LLMutils(String apiKey, String baseUrl, String model) {
    this(apiKey, baseUrl, model, 120);
  }

  public LLMutils(String apiKey, String baseUrl, String model, OkHttpClient client) {
    this(apiKey, baseUrl, model, client, null, 16384);
  }

  private LLMutils(
      String apiKey,
      String baseUrl,
      String model,
      OkHttpClient client,
      Boolean enableThinking,
      int maxOutputTokens) {
    if (maxOutputTokens < 1 || maxOutputTokens > 16384)
      throw new IllegalArgumentException("LLM_MAX_OUTPUT_TOKENS must be between 1 and 16384");
    this.apiKey = apiKey;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.model = model;
    this.client = client;
    this.enableThinking = enableThinking;
    this.maxOutputTokens = maxOutputTokens;
    org.slf4j.LoggerFactory.getLogger(LLMutils.class)
        .info(
            "llm_configured model={} enableThinking={}",
            model,
            enableThinking == null ? "provider-default" : enableThinking);
  }

  private static Boolean parseThinking(String value) {
    if (value == null || value.isBlank()) return null;
    if (value.equalsIgnoreCase("true")) return true;
    if (value.equalsIgnoreCase("false")) return false;
    throw new IllegalArgumentException("LLM_ENABLE_THINKING must be true, false or empty");
  }

  public String buildRequestBody(String systemPrompt, String userPrompt, Boolean stream) {
    ensureConfigured();
    List<Map<String, String>> messages = new ArrayList<>();
    if (systemPrompt != null && !systemPrompt.isBlank())
      messages.add(Map.of("role", "system", "content", systemPrompt));
    messages.add(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt));
    return encodeRequest(messages, Boolean.TRUE.equals(stream));
  }

  private String encodeRequest(List<Map<String, String>> messages, boolean stream) {
    ensureConfigured();
    var payload = new LinkedHashMap<String, Object>();
    payload.put("model", model);
    payload.put("stream", stream);
    payload.put("messages", messages);
    payload.put("temperature", 0.7);
    payload.put("max_tokens", Math.min(4096, maxOutputTokens));
    if (stream) payload.put("stream_options", Map.of("include_usage", true));
    if (enableThinking != null) payload.put("enable_thinking", enableThinking);
    try {
      return mapper.writeValueAsString(payload);
    } catch (IOException e) {
      throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "SERIALIZATION_ERROR", "无法创建模型请求");
    }
  }

  public void ensureConfigured() {
    if (apiKey == null || apiKey.isBlank())
      throw new AppException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "MODEL_NOT_CONFIGURED",
          "未配置 LLM_API_KEY，请在后端 .env 中填写自己的模型密钥");
  }

  public Call newChatCall(String systemPrompt, String userPrompt, boolean stream) {
    return newCall(buildRequestBody(systemPrompt, userPrompt, stream), stream);
  }

  /** Small bounded JSON selection, separate from ordinary conversational generation. */
  public Call newJsonChatCall(String systemPrompt, String userPrompt) {
    return newJsonChatCall(systemPrompt, userPrompt, 512);
  }

  public Call newJsonChatCall(String systemPrompt, String userPrompt, int outputLimit) {
    if (outputLimit < 1 || outputLimit > 16384)
      throw new IllegalArgumentException("Invalid JSON output limit");
    try {
      var payload =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              mapper.readTree(buildRequestBody(systemPrompt, userPrompt, false));
      payload.put("max_tokens", Math.min(outputLimit, maxOutputTokens));
      payload.put("temperature", 0.2);
      payload.putObject("response_format").put("type", "json_object");
      return newCall(mapper.writeValueAsString(payload), false);
    } catch (IOException e) {
      throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "SERIALIZATION_ERROR", "无法创建模型请求");
    }
  }

  public Call newChatCall(List<Map<String, String>> messages, boolean stream) {
    return newCall(encodeRequest(messages, stream), stream);
  }

  /** Short plans retain their old allowance; longer plans share the configured safety ceiling. */
  public int planningOutputLimit(int days) {
    if (days < 1 || days > 30) throw new IllegalArgumentException("Invalid planning days");
    return Math.min(maxOutputTokens, Math.max(4096, 1200 + days * 700));
  }

  private Call newCall(String body, boolean stream) {
    try {
      return client.newCall(
          new Request.Builder()
              .url(baseUrl + "/chat/completions")
              .header("Authorization", "Bearer " + apiKey)
              .header("Accept", stream ? "text/event-stream" : "application/json")
              .tag(ModelTrace.Attempt.class, new ModelTrace.Attempt(model))
              .post(RequestBody.create(body, MediaType.get("application/json; charset=utf-8")))
              .build());
    } catch (IllegalArgumentException e) {
      throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_NOT_CONFIGURED", "模型接口地址配置不正确");
    }
  }

  public String chat(String systemPrompt, String userPrompt) {
    return readCompletion(newChatCall(systemPrompt, userPrompt, false));
  }

  public String readCompletion(Call call) {
    ModelTrace.Attempt attempt = call.request().tag(ModelTrace.Attempt.class);
    Throwable failure = null;
    try (Response response = call.execute()) {
      checkStatus(response.code());
      if (response.body() == null) throw invalid();
      JsonNode root = mapper.readTree(response.body().string());
      if (root == null || root.isNull()) throw invalid();
      if (attempt != null) attempt.usage(root);
      JsonNode choice = root.path("choices").path(0);
      checkFinish(choice.path("finish_reason").asText());
      String content = choice.path("message").path("content").asText("");
      if (content.isBlank()) throw invalid();
      if (attempt != null) attempt.content();
      return content;
    } catch (IOException e) {
      var error = translate(call, e);
      failure = error;
      throw error;
    } catch (RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      if (attempt != null) attempt.finish(failure);
    }
  }

  /** Transport only. Spring AI owns tool dispatch and conversation history. */
  public Call newToolChatCall(List<Map<String, Object>> messages,
      List<Map<String, Object>> tools, int outputLimit) {
    ensureConfigured();
    var payload = new LinkedHashMap<String, Object>();
    payload.put("model", model);
    payload.put("messages", messages);
    payload.put("stream", false);
    payload.put("temperature", 0.2);
    payload.put("max_tokens", Math.min(maxOutputTokens, outputLimit));
    payload.put("tools", tools);
    payload.put("tool_choice", "auto");
    payload.put("parallel_tool_calls", false);
    if (enableThinking != null) payload.put("enable_thinking", enableThinking);
    try {
      String body = mapper.writeValueAsString(payload);
      if (body.length() > 500000)
        throw new AppException(HttpStatus.BAD_GATEWAY, "AGENT_CONTEXT_LIMIT", "规划上下文过长，请减少天数后重试");
      return newCall(body, false);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot encode agent request", e);
    }
  }

  public JsonNode readToolCompletion(Call call) {
    ModelTrace.Attempt attempt = call.request().tag(ModelTrace.Attempt.class);
    Throwable failure = null;
    try (Response response = call.execute()) {
      checkStatus(response.code());
      if (response.body() == null) throw invalid();
      byte[] bytes = response.body().byteStream().readNBytes(1048577);
      if (bytes.length > 1048576) throw invalid();
      JsonNode root = mapper.readTree(bytes);
      if (root == null || root.has("error")) throw invalid();
      if (attempt != null) attempt.usage(root);
      var choice = root.path("choices").path(0);
      String finish = choice.path("finish_reason").asText();
      checkFinish(finish);
      if (!Set.of("stop", "tool_calls").contains(finish)) throw invalid();
      if (!choice.path("message").isObject()) throw invalid();
      if (attempt != null) attempt.content();
      return choice.path("message");
    } catch (IOException e) {
      var error = translate(call, e);
      failure = error;
      throw error;
    } catch (RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      if (attempt != null) attempt.finish(failure);
    }
  }

  /** Stream the provider's SSE without accumulating the complete answer in memory. */
  public void readStream(Call call, Consumer<String> onContent) {
    ModelTrace.Attempt attempt = call.request().tag(ModelTrace.Attempt.class);
    Throwable failure = null;
    try (Response response = call.execute()) {
      checkStatus(response.code());
      if (response.body() == null) throw invalid();
      boolean sawFinish = false;
      boolean sawDone = false;
      boolean sawContent = false;
      StringBuilder eventData = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
        String line;
        while (!sawDone && (line = reader.readLine()) != null) {
          if (call.isCanceled()) throw new CancellationException();
          if (!line.isEmpty()) {
            if (line.startsWith("data:")) {
              if (eventData.length() > 0) eventData.append('\n');
              eventData.append(line.substring(5).stripLeading());
              if (eventData.length() > 1048576) throw invalid();
            }
            continue;
          }
          if (eventData.length() == 0) continue;
          String data = eventData.toString();
          eventData.setLength(0);
          if ("[DONE]".equals(data)) {
            sawDone = true;
            break;
          }
          JsonNode root = mapper.readTree(data);
          if (root == null || root.isNull()) throw invalid();
          if (attempt != null) attempt.usage(root);
          if (root.has("error")) throw invalid();
          JsonNode choice = root.path("choices").path(0);
          if (!choice.path("finish_reason").isMissingNode()
              && !choice.path("finish_reason").isNull()) {
            checkFinish(choice.path("finish_reason").asText());
            sawFinish = true;
          }
          JsonNode content = choice.path("delta").path("content");
          if (content.isTextual() && !content.asText().isEmpty()) {
            sawContent = true;
            if (attempt != null) attempt.content();
            onContent.accept(content.asText());
          }
        }
      }
      if (!sawDone && (!sawFinish || eventData.length() > 0)) {
        throw new AppException(HttpStatus.BAD_GATEWAY, "MODEL_STREAM_INTERRUPTED", "模型连接提前结束，请重试");
      }
      if (!sawContent) throw invalid();
    } catch (IOException e) {
      var error = translate(call, e);
      failure = error;
      throw error;
    } catch (RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      if (attempt != null) attempt.finish(failure);
    }
  }

  private void checkFinish(String reason) {
    if ("length".equals(reason))
      throw new AppException(
          HttpStatus.BAD_GATEWAY, "MODEL_OUTPUT_TRUNCATED", "模型回答达到长度限制，请缩短要求后重试");
    if ("content_filter".equals(reason))
      throw new AppException(
          HttpStatus.BAD_GATEWAY, "MODEL_CONTENT_FILTERED", "模型未能回答该问题，请调整问题后重试");
  }

  private void checkStatus(int status) {
    if (status >= 200 && status < 300) return;
    if (status == 401 || status == 403)
      throw new AppException(HttpStatus.BAD_GATEWAY, "MODEL_AUTH_ERROR", "模型鉴权失败，请检查后端密钥和模型权限");
    if (status == 429)
      throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_RATE_LIMITED", "模型服务限流，请稍后重试");
    if (status == 400 || status == 404 || status == 422)
      throw new AppException(
          HttpStatus.BAD_GATEWAY, "MODEL_REQUEST_REJECTED", "模型拒绝了请求，请检查模型配置或调整输入");
    throw new AppException(HttpStatus.BAD_GATEWAY, "MODEL_UNAVAILABLE", "模型服务暂时不可用，请稍后重试");
  }

  private RuntimeException translate(Call call, IOException error) {
    if (call.isCanceled() && !(error instanceof InterruptedIOException))
      return new CancellationException();
    if (error instanceof InterruptedIOException)
      return new AppException(HttpStatus.GATEWAY_TIMEOUT, "MODEL_TIMEOUT", "模型响应超时，请稍后重试");
    if (error instanceof com.fasterxml.jackson.core.JsonProcessingException) return invalid();
    return new AppException(HttpStatus.BAD_GATEWAY, "MODEL_CONNECTION_ERROR", "无法连接模型服务，请稍后重试");
  }

  private AppException invalid() {
    return new AppException(HttpStatus.BAD_GATEWAY, "INVALID_MODEL_RESPONSE", "模型返回的内容格式不正确，请重试");
  }

  @PreDestroy
  public void close() {
    client.dispatcher().cancelAll();
    client.dispatcher().executorService().shutdownNow();
    client.connectionPool().evictAll();
  }
}

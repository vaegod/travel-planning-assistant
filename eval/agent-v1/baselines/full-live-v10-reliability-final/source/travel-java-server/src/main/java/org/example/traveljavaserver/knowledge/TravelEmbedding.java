package org.example.traveljavaserver.knowledge;

import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "knowledge.enabled", havingValue = "true")
public class TravelEmbedding implements EmbeddingModel {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(TravelEmbedding.class);
  private final ObjectMapper json = new ObjectMapper();
  private final OkHttpClient http =
      new OkHttpClient.Builder()
          .connectTimeout(5, TimeUnit.SECONDS)
          .callTimeout(15, TimeUnit.SECONDS)
          .retryOnConnectionFailure(false)
          .build();
  private final String key, base, model;
  private final int dimensions;

  public TravelEmbedding(
      @Value("${llm.api-key}") String key,
      @Value("${knowledge.embedding-base-url:${llm.base-url}}") String base,
      @Value("${knowledge.embedding-model:BAAI/bge-m3}") String model,
      @Value("${knowledge.dimensions:1024}") int dimensions) {
    this.key = key;
    this.base = base.replaceAll("/+$", "");
    this.model = model;
    this.dimensions = dimensions;
    if (dimensions < 1 || dimensions > 4096)
      throw new IllegalArgumentException("Invalid embedding dimensions");
  }

  public String model() {
    return model;
  }

  @Override
  public int dimensions() {
    return dimensions;
  }

  @Override
  public float[] embed(Document document) {
    return embed(document.getText());
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    var texts = request.getInstructions();
    if (texts == null
        || texts.isEmpty()
        || texts.size() > 16
        || texts.stream().anyMatch(t -> t == null || t.isBlank() || t.length() > 2000))
      throw RagRun.error("INVALID_EMBEDDING_INPUT", "向量化输入为空或超过批次限制");
    if (key == null || key.isBlank())
      throw RagRun.error("EMBEDDING_NOT_CONFIGURED", "请配置后端模型密钥后导入资料");
    var run = RagRun.current();
    Call call;
    try {
      call =
          http.newCall(
              new Request.Builder()
                  .url(base + "/embeddings")
                  .header("Authorization", "Bearer " + key)
                  .post(
                      RequestBody.create(
                          json.writeValueAsString(
                              Map.of("model", model, "input", texts, "encoding_format", "float")),
                          MediaType.get("application/json")))
                  .build());
    } catch (Exception e) {
      throw RagRun.error("EMBEDDING_NOT_CONFIGURED", "向量模型配置不正确");
    }
    call.timeout().timeout(Math.min(15000, run.remaining()), TimeUnit.MILLISECONDS);
    run.track(call);
    long start = System.nanoTime();
    String outcome = "error";
    Long tokens = null;
    try (Response response = call.execute()) {
      if (!response.isSuccessful())
        throw RagRun.error(
            response.code() == 429 ? "EMBEDDING_RATE_LIMITED" : "EMBEDDING_UNAVAILABLE",
            "向量服务未能完成请求，原有资料保持不变");
      if (response.body() == null) throw invalid();
      JsonNode root = json.readTree(response.body().string());
      JsonNode data = root.path("data");
      if (!data.isArray() || data.size() != texts.size()) throw invalid();
      List<Embedding> vectors = new ArrayList<>(Collections.nCopies(texts.size(), null));
      for (var item : data) {
        int index = item.path("index").asInt(-1);
        var value = item.path("embedding");
        if (index < 0
            || index >= texts.size()
            || vectors.get(index) != null
            || !value.isArray()
            || value.size() != dimensions) throw invalid();
        float[] vector = new float[dimensions];
        double norm = 0;
        for (int j = 0; j < dimensions; j++) {
          if (!value.get(j).isNumber()) throw invalid();
          vector[j] = (float) value.get(j).asDouble();
          if (!Float.isFinite(vector[j])) throw invalid();
          norm += (double) vector[j] * vector[j];
        }
        if (norm <= 0) throw invalid();
        vectors.set(index, new Embedding(vector, index));
      }
      if (root.path("usage").path("prompt_tokens").isIntegralNumber())
        tokens = root.path("usage").path("prompt_tokens").asLong();
      outcome = "success";
      return new EmbeddingResponse(vectors);
    } catch (java.io.IOException e) {
      run.remaining();
      throw RagRun.error("EMBEDDING_CONNECTION_ERROR", "向量查询失败或超时，请稍后重试");
    } finally {
      run.calls.remove(call);
      LOG.info(
          "embedding_call requestId={} model={} inputs={} status={} durationMs={} inputTokens={}",
          run.id,
          model,
          texts.size(),
          outcome,
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
          tokens);
    }
  }

  private RuntimeException invalid() {
    return RagRun.error("INVALID_EMBEDDING_RESPONSE", "向量服务返回格式、维度或数值不正确");
  }

  @PreDestroy
  public void close() {
    http.dispatcher().cancelAll();
    http.connectionPool().evictAll();
    http.dispatcher().executorService().shutdownNow();
  }
}

package org.example.traveljavaserver.utils;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.example.traveljavaserver.common.AppException;
import org.junit.jupiter.api.*;

class LLMutilsTests {
  private HttpServer server;
  private LLMutils llm;
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private int status;
  private String body;

  @BeforeEach
  void setup() throws IOException {
    status = 200;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          }
        });
    server.start();
    llm =
        new LLMutils("test-key", "http://127.0.0.1:" + server.getAddress().getPort(), "test-model");
  }

  @AfterEach
  void close() {
    llm.close();
    server.stop(0);
  }

  @Test
  void nonThinkingConfigurationAppliesToPlanningAndMultiTurnStreaming() throws Exception {
    llm.close();
    llm =
        new LLMutils(
            "test-key",
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "Qwen/Qwen3.5-35B-A3B",
            10,
            "false");
    body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
    assertEquals("ok", llm.chat("system", "select IDs"));
    var json = new ObjectMapper().readTree(requestBody.get());
    assertEquals("Qwen/Qwen3.5-35B-A3B", json.path("model").asText());
    assertTrue(json.path("enable_thinking").isBoolean());
    assertFalse(json.path("enable_thinking").asBoolean());
    assertFalse(json.path("stream").asBoolean());
    var messages =
        List.of(
            Map.of("role", "system", "content", "system"),
            Map.of("role", "user", "content", "杭州3天"),
            Map.of("role", "assistant", "content", "已知要求"),
            Map.of("role", "user", "content", "预算改成1500"));
    body =
        "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"private reasoning\"}}]}\n\n"
            + "data:"
            + " {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n";
    List<String> output = new ArrayList<>();
    llm.readStream(llm.newChatCall(messages, true), output::add);
    json = new ObjectMapper().readTree(requestBody.get());
    assertFalse(json.path("enable_thinking").asBoolean());
    assertTrue(json.path("stream").asBoolean());
    assertEquals(new ObjectMapper().valueToTree(messages), json.path("messages"));
    assertEquals(List.of("ok"), output);
  }

  @Test
  void thinkingCanBeEnabledOrLeftToProviderWithoutBreakingOtherModels() throws Exception {
    assertFalse(
        new ObjectMapper()
            .readTree(llm.buildRequestBody(null, "test", false))
            .has("enable_thinking"));
    llm.close();
    llm =
        new LLMutils(
            "test-key",
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "other-model",
            10,
            "true");
    assertTrue(
        new ObjectMapper()
            .readTree(llm.buildRequestBody(null, "test", false))
            .path("enable_thinking")
            .asBoolean());
  }

  @Test
  void rejectsMisspelledThinkingConfiguration() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new LLMutils("test-key", "http://127.0.0.1:1", "test-model", 10, "flase"));
  }

  @Test
  void planningUsesBoundedJsonWhileOrdinaryChatKeepsTextOutput() throws Exception {
    body =
        "{\"choices\":[{\"message\":{\"content\":\"{\\\"placeIds\\\":[\\\"node/1\\\"]}\"},\"finish_reason\":\"stop\"}]}";
    assertEquals(
        "{\"placeIds\":[\"node/1\"]}",
        llm.readCompletion(llm.newJsonChatCall("select from IDs", "node/1")));
    var json = new ObjectMapper().readTree(requestBody.get());
    assertEquals("json_object", json.path("response_format").path("type").asText());
    assertEquals(512, json.path("max_tokens").asInt());
    assertEquals(0.2, json.path("temperature").asDouble());
    assertFalse(json.path("stream").asBoolean());
    var chat = new ObjectMapper().readTree(llm.buildRequestBody(null, "杭州旅游建议", true));
    assertFalse(chat.has("response_format"));
    assertTrue(chat.path("stream").asBoolean());
    assertEquals(0.7, chat.path("temperature").asDouble());
  }

  @Test
  void planningOutputGrowsWithDaysButHonorsGlobalCeilingAndChatAllowance() throws Exception {
    assertEquals(4096, llm.planningOutputLimit(1));
    assertEquals(4096, llm.planningOutputLimit(4));
    assertEquals(4700, llm.planningOutputLimit(5));
    assertEquals(16384, llm.planningOutputLimit(30));
    assertThrows(IllegalArgumentException.class, () -> llm.planningOutputLimit(31));
    assertThrows(IllegalArgumentException.class, () -> llm.newJsonChatCall("", "", 16385));
    assertEquals(4096, new ObjectMapper().readTree(llm.buildRequestBody("", "", true)).path("max_tokens").asInt());
    var limited = new LLMutils("test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
        "test-model", 10, "false", 3000);
    try {
      assertEquals(3000, limited.planningOutputLimit(30));
      body = "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
      assertEquals("ok", limited.readCompletion(limited.newJsonChatCall("", "", 16384)));
      assertEquals(3000, new ObjectMapper().readTree(requestBody.get()).path("max_tokens").asInt());
    } finally {
      limited.close();
    }
  }

  @Test
  void serializesBothPromptsWithQuotesNewlinesTabsAndBackslashes() throws Exception {
    body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
    String prompt = "引号\"、反斜杠\\、换行\n制表\t回车\r";
    assertEquals("ok", llm.chat(prompt, prompt));
    var json = new ObjectMapper().readTree(requestBody.get());
    assertEquals(prompt, json.path("messages").get(0).path("content").asText());
    assertEquals(prompt, json.path("messages").get(1).path("content").asText());
  }

  @Test
  void mapsUpstreamErrorsWithoutLeakingProviderBody() {
    for (int code : new int[] {401, 403, 429, 400, 404, 500}) {
      status = code;
      body = "private-provider-details";
      AppException error = assertThrows(AppException.class, () -> llm.chat(null, "test"));
      assertFalse(error.getMessage().contains(body));
      assertEquals(code == 429 ? 503 : 502, error.getStatus().value());
      if (code == 401 || code == 403) assertEquals("MODEL_AUTH_ERROR", error.getErrorCode());
      if (code == 429) assertEquals("MODEL_RATE_LIMITED", error.getErrorCode());
    }
  }

  @Test
  void rejectsMalformedEmptyAndLengthTruncatedResponses() {
    for (String invalid :
        List.of(
            "not-json",
            "null",
            "{}",
            "{\"choices\":[]}",
            "{\"choices\":[{\"message\":{\"content\":\"\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}")) {
      body = invalid;
      assertThrows(AppException.class, () -> llm.chat(null, "test"));
    }
  }

  @Test
  void readsProviderSseWithoutSpaceAndWithCrlfAndComments() {
    body =
        ":heartbeat\r\n\r\ndata:{\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\r\n\r\n"
            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n";
    List<String> chunks = new ArrayList<>();
    llm.readStream(llm.newChatCall(null, "test", true), chunks::add);
    assertEquals(List.of("你好"), chunks);
  }

  @Test
  void rejectsAbruptStreamInvalidFramesAndEmptyDone() {
    for (String invalid :
        List.of(
            "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n",
            "data: invalid\n\n",
            "data: [DONE]\n\n")) {
      body = invalid;
      assertThrows(
          AppException.class,
          () -> llm.readStream(llm.newChatCall(null, "test", true), chunk -> {}));
    }
  }

  @Test
  void readTimeoutIsDistinguishedFromConnectionFailure() throws Exception {
    server.removeContext("/chat/completions");
    server.createContext(
        "/chat/completions",
        exchange -> {
          try {
            Thread.sleep(200);
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    llm.close();
    llm =
        new LLMutils(
            "test-key",
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "test-model",
            new okhttp3.OkHttpClient.Builder()
                .callTimeout(40, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build());
    AppException error = assertThrows(AppException.class, () -> llm.chat(null, "test"));
    assertEquals("MODEL_TIMEOUT", error.getErrorCode());
    assertEquals(504, error.getStatus().value());
  }

  @Test
  void connectionFailureIsDistinguishedFromTimeout() {
    server.stop(0);
    AppException error = assertThrows(AppException.class, () -> llm.chat(null, "test"));
    assertEquals("MODEL_CONNECTION_ERROR", error.getErrorCode());
    assertEquals(502, error.getStatus().value());
  }
}

package org.example.traveljavaserver.utils;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.example.traveljavaserver.common.AppException;
import org.junit.jupiter.api.*;

class ModelTraceTests {
  HttpServer server;
  LLMutils llm;
  String body;
  int status = 200;
  AtomicReference<String> request = new AtomicReference<>();

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat/completions",
        e -> {
          request.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          e.sendResponseHeaders(status, bytes.length);
          try (var output = e.getResponseBody()) {
            output.write(bytes);
          }
        });
    server.start();
    llm =
        new LLMutils(
            "private-test-key", "http://127.0.0.1:" + server.getAddress().getPort(), "test-model");
  }

  @AfterEach
  void close() {
    llm.close();
    server.stop(0);
  }

  @Test
  void reportsProviderUsageWithCorrelationWithoutPromptOrAnswer() {
    body =
        "{\"choices\":[{\"message\":{\"content\":\"private"
            + " answer\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":0},\"completion_tokens_details\":{\"reasoning_tokens\":0}}}";
    List<ModelTrace.Entry> records = new ArrayList<>();
    try (var scope = ModelTrace.open("request-1", ModelTrace.PLAN_PROMPT, records::add)) {
      assertEquals("private answer", llm.chat("private prompt", "private user"));
    }
    var record = records.get(0);
    assertEquals("request-1", record.requestId());
    assertEquals(ModelTrace.PLAN_PROMPT, record.promptVersion());
    assertEquals(10L, record.inputTokens());
    assertEquals(2L, record.outputTokens());
    assertEquals(0L, record.cachedInputTokens());
    assertTrue(record.usageComplete());
    assertNotNull(record.firstContentMs());
    assertFalse(record.toString().contains("private"));
    llm.chat(null, "outside scope");
    assertEquals(1, records.size(), "ThreadLocal scope must be removed");
  }

  @Test
  void capturesUsageOnlyStreamFrameAndRequestsIt() throws Exception {
    body =
        "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":3}}\n\n"
            + "data: [DONE]\n\n";
    List<ModelTrace.Entry> records = new ArrayList<>();
    List<String> content = new ArrayList<>();
    try (var scope = ModelTrace.open("stream-1", ModelTrace.CHAT_PROMPT, records::add)) {
      llm.readStream(llm.newChatCall(null, "hi", true), content::add);
    }
    assertEquals(List.of("你好"), content);
    assertEquals(20L, records.get(0).inputTokens());
    assertEquals(3L, records.get(0).outputTokens());
    assertNull(records.get(0).cachedInputTokens());
    assertTrue(records.get(0).usageComplete());
    var json = new ObjectMapper().readTree(request.get());
    assertTrue(json.path("stream_options").path("include_usage").asBoolean());
    assertEquals(4096, json.path("max_tokens").asInt());
  }

  @Test
  void errorUsageIsUnknownAndInvalidOutputLimitFailsFast() {
    status = 401;
    body = "private upstream details";
    List<ModelTrace.Entry> records = new ArrayList<>();
    try (var scope = ModelTrace.open("failure-1", ModelTrace.CHAT_PROMPT, records::add)) {
      assertThrows(AppException.class, () -> llm.chat(null, "test"));
    }
    var record = records.get(0);
    assertEquals("MODEL_AUTH_ERROR", record.status());
    assertNull(record.inputTokens());
    assertNull(record.outputTokens());
    assertFalse(record.usageComplete());
    assertNull(record.firstContentMs());
    assertThrows(
        IllegalArgumentException.class,
        () -> new LLMutils("key", "http://localhost:1", "model", 5, "false", 0));
  }
}

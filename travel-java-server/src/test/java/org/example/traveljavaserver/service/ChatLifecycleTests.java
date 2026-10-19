package org.example.traveljavaserver.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "chat.timeout-ms=3000",
      "chat.heartbeat-ms=25",
      "chat.workers=2",
      "chat.queue-capacity=2"
    })
class ChatLifecycleTests {
  private static final ObjectMapper mapper = new ObjectMapper();
  private static final ConcurrentHashMap<String, Probe> probes = new ConcurrentHashMap<>();
  private static final ExecutorService providerThreads =
      Executors.newCachedThreadPool(
          task -> {
            Thread thread = new Thread(task, "test-model-provider");
            thread.setDaemon(true);
            return thread;
          });
  private static final HttpServer provider = startProvider();
  @LocalServerPort private int port;
  @Autowired private TravelService service;
  private HttpClient client;
  private org.example.traveljavaserver.TestApi api;
  @Autowired private DataService data;
  private final Map<String, String> conversationIds = new HashMap<>();

  @BeforeEach
  void authenticate() throws Exception {
    api = new org.example.traveljavaserver.TestApi(port);
    api.registerLogin();
    client = api.client;
  }

  private final List<String> ids = new ArrayList<>();
  private final List<InputStream> bodies = new ArrayList<>();

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("llm.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
    registry.add("llm.api-key", () -> "test-key");
    registry.add("llm.model", () -> "test-model");
  }

  private static HttpServer startProvider() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(providerThreads);
      server.createContext(
          "/chat/completions",
          exchange -> {
            var request = mapper.readTree(exchange.getRequestBody().readAllBytes());
            String message =
                request
                    .path("messages")
                    .get(request.path("messages").size() - 1)
                    .path("content")
                    .asText();
            if (message.contains("unauthorized") || message.contains("rate-limit")) {
              byte[] bytes = "private-provider-error".getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(
                  message.contains("unauthorized") ? 401 : 429, bytes.length);
              try (var output = exchange.getResponseBody()) {
                output.write(bytes);
              }
              return;
            }
            if (!request.path("stream").asBoolean()) {
              Map<String, String> slot =
                  Map.of(
                      "spot",
                      "测试景点",
                      "duration",
                      "1小时",
                      "ticket",
                      "0元",
                      "transportation",
                      "步行",
                      "description",
                      "测试资料");
              String plan =
                  message.contains("malformed-plan")
                      ? "{}"
                      : mapper.writeValueAsString(
                          Map.of(
                              "success",
                              true,
                              "city",
                              "测试城",
                              "days",
                              1,
                              "totalBudget",
                              1000,
                              "dailyItinerary",
                              List.of(
                                  Map.of(
                                      "day",
                                      1,
                                      "morning",
                                      slot,
                                      "afternoon",
                                      slot,
                                      "evening",
                                      slot))));
              byte[] bytes =
                  mapper.writeValueAsBytes(
                      Map.of("choices", List.of(Map.of("message", Map.of("content", plan)))));
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, bytes.length);
              try (var output = exchange.getResponseBody()) {
                output.write(bytes);
              }
              return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            Probe probe = probes.computeIfAbsent(message, ignored -> new Probe());
            try (var output = exchange.getResponseBody()) {
              if (message.startsWith("wait-")) {
                probe.started.countDown();
                for (int i = 0; i < 500; i++) {
                  output.write(": still thinking\n\n".getBytes(StandardCharsets.UTF_8));
                  output.flush();
                  try {
                    Thread.sleep(20);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                  }
                }
                return;
              }
              output.write(
                  "data: {\"choices\":[{\"delta\":{\"content\":\"首段\"}}]}\n\n"
                      .getBytes(StandardCharsets.UTF_8));
              output.flush();
              probe.started.countDown();
              if (message.startsWith("slow-")) {
                for (int i = 0; i < 500; i++) {
                  output.write(": upstream heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                  output.flush();
                  try {
                    Thread.sleep(20);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                  }
                }
              } else if (message.equals("abrupt")) {
                return;
              } else {
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
              }
            } catch (IOException ignored) {
              // Cancellation must close this real upstream socket, not only the browser UI.
            } finally {
              probe.closed.countDown();
            }
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @AfterEach
  void cleanup() throws Exception {
    ids.forEach(id -> service.cancel(api.userId, id));
    for (InputStream body : bodies) body.close();
    eventually(
        () ->
            service.activeStreamCount() == 0
                && service.queuedStreamCount() == 0
                && service.runningStreamCount() == 0);
  }

  @AfterAll
  static void shutdownProvider() {
    provider.stop(0);
    providerThreads.shutdownNow();
  }

  private String newId() {
    String id = UUID.randomUUID().toString();
    ids.add(id);
    return id;
  }

  private HttpRequest request(String path, String json) {
    if (path.equals("chat")) {
      try {
        var value = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(json);
        String requestId = value.path("requestId").asText();
        String conversationId = conversationIds.get(requestId);
        if (conversationId == null) {
          conversationId = api.conversation();
          conversationIds.put(requestId, conversationId);
        }
        value.put("conversationId", conversationId);
        json = value.toString();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    return api.request("POST", "/api/travel/" + path, json);
  }

  private HttpResponse<InputStream> stream(String id, String message) throws Exception {
    var response =
        client.send(
            request("chat", mapper.writeValueAsString(Map.of("requestId", id, "message", message))),
            HttpResponse.BodyHandlers.ofInputStream());
    bodies.add(response.body());
    return response;
  }

  private List<com.fasterxml.jackson.databind.JsonNode> events(InputStream body) throws Exception {
    List<com.fasterxml.jackson.databind.JsonNode> result = new ArrayList<>();
    try (var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null)
        if (line.startsWith("data:")) result.add(mapper.readTree(line.substring(5)));
    }
    return result;
  }

  private void cancelHttp(String id) throws Exception {
    var response =
        client.send(
            api.request("DELETE", "/api/travel/chat/" + id, null),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
  }

  private static void eventually(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
    assertTrue(condition.getAsBoolean(), "Active streams or queued work were not released");
  }

  @Test
  void normalStreamHasStartChunksAndExactlyOneDone() throws Exception {
    String id = newId();
    var response = stream(id, "normal");
    assertEquals(200, response.statusCode());
    var frames = events(response.body());
    assertEquals(
        List.of("start", "chunk", "done"),
        frames.stream().map(event -> event.path("type").asText()).toList());
    assertTrue(frames.stream().allMatch(event -> event.path("requestId").asText().equals(id)));
    assertEquals("首段", frames.get(1).path("content").asText());
    eventually(() -> service.activeStreamCount() == 0);
  }

  @Test
  void explicitCancelClosesUpstreamAndAllowsImmediateNewRequest() throws Exception {
    String id = newId(), message = "slow-" + id;
    Probe probe = probes.computeIfAbsent(message, ignored -> new Probe());
    var response = stream(id, message);
    assertTrue(probe.started.await(2, TimeUnit.SECONDS));
    cancelHttp(id);
    assertTrue(
        probe.closed.await(2, TimeUnit.SECONDS), "Upstream socket was not closed after cancel");
    var frames = events(response.body());
    assertEquals(
        1, frames.stream().filter(event -> event.path("type").asText().equals("done")).count());
    eventually(() -> service.activeStreamCount() == 0);
    var resumed = events(stream(newId(), "normal-after-cancel").body());
    assertEquals("done", resumed.get(resumed.size() - 1).path("type").asText());
    cancelHttp(id); // Idempotent after completion.
  }

  @Test
  void timeoutSendsOneErrorAndClosesUpstream() throws Exception {
    String id = newId(), message = "slow-" + id;
    Probe probe = probes.computeIfAbsent(message, ignored -> new Probe());
    var frames = events(stream(id, message).body());
    assertTrue(
        frames.stream().anyMatch(event -> event.path("code").asText().equals("STREAM_TIMEOUT")));
    assertEquals(
        1, frames.stream().filter(event -> event.path("type").asText().equals("error")).count());
    assertFalse(frames.stream().anyMatch(event -> event.path("type").asText().equals("done")));
    assertTrue(probe.closed.await(2, TimeUnit.SECONDS));
  }

  @Test
  void disconnectWithoutCancelIsDetectedByHeartbeatAndClosesUpstream() throws Exception {
    String id = newId(), message = "slow-" + id;
    Probe probe = probes.computeIfAbsent(message, ignored -> new Probe());
    byte[] json =
        mapper.writeValueAsBytes(
            Map.of("requestId", id, "message", message, "conversationId", api.conversation()));
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoLinger(true, 0);
      var output = socket.getOutputStream();
      output.write(
          ("POST /api/travel/chat HTTP/1.1\r\nHost: localhost\r\nCookie: "
                  + api.cookieHeader()
                  + "\r\n"
                  + api.csrfHeader
                  + ": "
                  + api.csrfToken
                  + "\r\nContent-Type: application/json\r\nContent-Length: "
                  + json.length
                  + "\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      output.write(json);
      output.flush();
      assertTrue(probe.started.await(2, TimeUnit.SECONDS));
    }
    assertTrue(
        probe.closed.await(1, TimeUnit.SECONDS),
        "Heartbeat did not close upstream before the 3s overall timeout");
    eventually(() -> service.activeStreamCount() == 0);
  }

  @Test
  void duplicateRequestIdIsRejectedWith409() throws Exception {
    String id = newId();
    stream(id, "slow-" + id);
    var duplicate =
        client.send(
            request(
                "chat", mapper.writeValueAsString(Map.of("requestId", id, "message", "duplicate"))),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(409, duplicate.statusCode());
    assertEquals("DUPLICATE_REQUEST", mapper.readTree(duplicate.body()).path("errorCode").asText());
  }

  @Test
  void boundedWorkersRejectOverloadAndQueuedCancellationReleasesQueue() throws Exception {
    for (int i = 0; i < 4; i++) {
      String id = newId();
      assertEquals(200, stream(id, "slow-" + id).statusCode());
    }
    String rejectedId = newId();
    var response =
        client.send(
            request(
                "chat",
                mapper.writeValueAsString(Map.of("requestId", rejectedId, "message", "overload"))),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(503, response.statusCode());
    assertEquals("SERVICE_BUSY", mapper.readTree(response.body()).path("errorCode").asText());
    assertTrue(service.queuedStreamCount() <= 2);
    cancelHttp(ids.get(3));
    assertTrue(service.queuedStreamCount() <= 1);
  }

  @Test
  void upstreamFailuresUseSseErrorsAfterStartAndHttpErrorsForRecommendations() throws Exception {
    for (String message : List.of("unauthorized", "rate-limit", "abrupt")) {
      var frames = events(stream(newId(), message).body());
      assertEquals("start", frames.get(0).path("type").asText());
      assertEquals("error", frames.get(frames.size() - 1).path("type").asText());
      assertFalse(frames.stream().anyMatch(event -> event.path("type").asText().equals("done")));
      assertFalse(frames.toString().contains("private-provider-error"));
    }
    var response =
        client.send(
            request("recommend", "{\"city\":\"unauthorized\",\"days\":1,\"budget\":1000}"),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(502, response.statusCode());
    assertEquals(502, mapper.readTree(response.body()).path("code").asInt());
    assertEquals("MODEL_AUTH_ERROR", mapper.readTree(response.body()).path("errorCode").asText());
  }

  @Test
  void invalidInputUses400ForBothHttpStatusAndBodyCode() throws Exception {
    for (String json :
        List.of(
            "{",
            "{\"city\":\" \",\"days\":1,\"budget\":1000}",
            "{\"city\":\"杭州\",\"days\":1.5,\"budget\":1000}",
            "{\"city\":\"杭州\",\"days\":1,\"budget\":1000001}")) {
      var response = client.send(request("recommend", json), HttpResponse.BodyHandlers.ofString());
      assertEquals(400, response.statusCode(), json);
      assertEquals(400, mapper.readTree(response.body()).path("code").asInt());
    }
    for (String message : List.of(" ", "长".repeat(2001))) {
      var response =
          client.send(
              request(
                  "chat",
                  mapper.writeValueAsString(Map.of("requestId", newId(), "message", message))),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, response.statusCode());
      assertEquals("INVALID_INPUT", mapper.readTree(response.body()).path("errorCode").asText());
    }
  }

  @Test
  void malformedPlanFailsAndNextValidPlanSucceeds() throws Exception {
    var failed =
        client.send(
            request("recommend", "{\"city\":\"malformed-plan\",\"days\":1,\"budget\":1000}"),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(502, failed.statusCode());
    assertEquals("INVALID_TRAVEL_PLAN", mapper.readTree(failed.body()).path("errorCode").asText());
    var valid =
        client.send(
            request("recommend", "{\"city\":\"测试城\",\"days\":1,\"budget\":1000}"),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, valid.statusCode());
    assertTrue(mapper.readTree(valid.body()).path("data").path("success").asBoolean());
  }

  @Test
  void cancelBeforeFirstModelContentClosesUpstream() throws Exception {
    String id = newId(), message = "wait-" + id;
    Probe probe = probes.computeIfAbsent(message, ignored -> new Probe());
    var response = stream(id, message);
    assertTrue(probe.started.await(1, TimeUnit.SECONDS));
    cancelHttp(id);
    assertTrue(probe.closed.await(1, TimeUnit.SECONDS));
    var frames = events(response.body());
    assertEquals(
        List.of("start", "done"),
        frames.stream().map(event -> event.path("type").asText()).toList());
  }

  @Test
  void shutdownCancelsRunningModelRequestAndReleasesWorkers() throws Exception {
    var localLlm =
        new org.example.traveljavaserver.utils.LLMutils(
            "test-key", "http://127.0.0.1:" + provider.getAddress().getPort(), "test-model");
    var localService = new TravelService(localLlm, data, 3000, 25, 1, 1);
    String id = UUID.randomUUID().toString(), message = "wait-" + id;
    Probe probe = probes.computeIfAbsent(message, ignored -> new Probe());
    try {
      localService.startMaintenance();
      localService.chat(api.userId, api.conversation(), id, message);
      assertTrue(probe.started.await(1, TimeUnit.SECONDS));
      localService.close();
      assertTrue(probe.closed.await(1, TimeUnit.SECONDS));
      eventually(
          () -> localService.activeStreamCount() == 0 && localService.runningStreamCount() == 0);
    } finally {
      localService.close();
      localLlm.close();
    }
  }

  @Test
  void unsupportedMethodKeepsItsHttpStatus() throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/travel/recommend"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(405, response.statusCode());
    assertEquals(405, mapper.readTree(response.body()).path("code").asInt());
  }

  private static class Probe {
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch closed = new CountDownLatch(1);
  }
}

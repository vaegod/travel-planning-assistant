package org.example.traveljavaserver;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DirectPlanningTests {
  static final ObjectMapper JSON = new ObjectMapper();
  static final AtomicInteger models = new AtomicInteger(), maps = new AtomicInteger();
  static volatile String mode = "ok";
  static volatile JsonNode captured;
  static volatile CountDownLatch started, release;
  static final ExecutorService threads =
      Executors.newCachedThreadPool(
          r -> {
            var t = new Thread(r, "direct-test-provider");
            t.setDaemon(true);
            return t;
          });
  static final HttpServer provider = provider();
  @LocalServerPort int port;

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.context.ApplicationContext context;

  TestApi a;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry p) {
    String url = "http://127.0.0.1:" + provider.getAddress().getPort();
    p.add("llm.base-url", () -> url);
    p.add("llm.api-key", () -> "fixture-key");
    p.add("planning.overpass-url", () -> "disabled-invalid-url");
    p.add("planning.osrm-url", () -> url);
    p.add("knowledge.dimensions", () -> "0");
    p.add("knowledge.min-score", () -> "-1");
  }

  static String match(String text, String regex) {
    var m = java.util.regex.Pattern.compile(regex).matcher(text);
    if (!m.find()) throw new IllegalStateException("Invalid direct test prompt");
    return m.group(1).strip();
  }

  static HttpServer provider() {
    try {
      var s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.setExecutor(threads);
      s.createContext(
          "/places",
          e -> {
            maps.incrementAndGet();
            send(e, 503, Map.of("error", "map disabled"));
          });
      s.createContext(
          "/route",
          e -> {
            maps.incrementAndGet();
            send(e, 503, Map.of("error", "route disabled"));
          });
      s.createContext(
          "/chat/completions",
          e -> {
            models.incrementAndGet();
            var payload = JSON.readTree(e.getRequestBody().readAllBytes());
            captured = payload;
            if (mode.equals("slow")) {
              started.countDown();
              try {
                release.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
              }
            }
            if (mode.equals("auth") || mode.equals("rate")) {
              send(e, mode.equals("auth") ? 401 : 429, Map.of("error", "private-provider-detail"));
              return;
            }
            String prompt = payload.path("messages").get(1).path("content").asText();
            String city = match(prompt, "目的地城市：([^\\n]+)"),
                budget = match(prompt, "预算：([\\d.]+)"),
                daysText = match(prompt, "旅行天数：(\\d+)");
            int days = Integer.parseInt(daysText);
            var plan = JSON.createObjectNode();
            plan.put("success", true);
            plan.put("city", mode.equals("wrong-city") ? "别的城市" : mode.equals("city-alias") ? "杭州" : city);
            plan.put("days", days);
            plan.put(
                "totalBudget",
                new java.math.BigDecimal(mode.equals("wrong-budget") ? "99" : budget));
            var itinerary = plan.putArray("dailyItinerary");
            for (int day = 1; day <= days; day++) {
              var row = itinerary.addObject();
              row.put("day", day);
              for (String time : List.of("morning", "afternoon", "evening")) {
                var slot = row.putObject(time);
                slot.put("spot", mode.equals("blank-spot") ? "" : "参考景点" + day + time);
                slot.put("duration", "1小时");
                slot.put("ticket", "估算20元");
                slot.put("transportation", "交通参考");
                slot.put("description", "模型生成测试资料");
                if (mode.equals("fake-evidence")) {
                  slot.put("sourceUrl", "https://example.com/fake");
                  slot.put("placeId", "node/fake");
                  slot.put("queriedAt", "2026-10-18T00:00:00Z");
                }
              }
            }
            if (mode.equals("wrong-days")) itinerary.remove(itinerary.size() - 1);
            if (mode.equals("fake-evidence")) {
              var ev = plan.putObject("evidence");
              ev.put("status", "validated");
              ev.put("attribution", "模型编造的查询");
              ev.putArray("traces");
              ev.putArray("modelCalls");
              plan.put("estimatedTotal", 1);
            }
            send(
                e,
                200,
                Map.of(
                    "choices",
                    List.of(
                        Map.of(
                            "message",
                            Map.of(
                                "content", mode.equals("bad-json") ? "not-json" : plan.toString()),
                            "finish_reason",
                            mode.equals("truncated") ? "length" : "stop"))));
          });
      s.start();
      return s;
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static void send(HttpExchange e, int status, Object body) throws java.io.IOException {
    byte[] bytes = JSON.writeValueAsBytes(body);
    e.getResponseHeaders().set("Content-Type", "application/json");
    e.sendResponseHeaders(status, bytes.length);
    try (var output = e.getResponseBody()) {
      output.write(bytes);
    } finally {
      e.close();
    }
  }

  @BeforeEach
  void setup() throws Exception {
    mode = "ok";
    models.set(0);
    maps.set(0);
    started = new CountDownLatch(2);
    release = new CountDownLatch(1);
    a = new TestApi(port);
    a.registerLogin();
  }

  @AfterEach
  void clean() {
    release.countDown();
  }

  @AfterAll
  static void stop() {
    provider.stop(0);
    threads.shutdownNow();
  }

  String request(String city, int days) throws Exception {
    return JSON.writeValueAsString(Map.of("city", city, "days", days, "budget", 2000));
  }

  JsonNode direct(String city, int days) throws Exception {
    return a.data("POST", "/api/travel/recommend", request(city, days));
  }

  @Test
  void disabledMapFlowDoesNotConstructClientsOrWorkersEvenWithInvalidMapConfiguration()
      throws Exception {
    assertTrue(
        context.getBeansOfType(org.example.traveljavaserver.planning.TravelTools.class).isEmpty());
    assertTrue(
        context
            .getBeansOfType(org.example.traveljavaserver.planning.PlanningEngine.class)
            .isEmpty());
    assertTrue(
        context.getBeansOfType(org.example.traveljavaserver.planning.PlanningJobs.class).isEmpty());
    assertEquals(410, a.send("GET", "/api/planning/jobs/" + UUID.randomUUID(), null).statusCode());
    assertEquals(0, maps.get());
    assertEquals(0, models.get());
  }

  @Test
  void disabledKnowledgeDoesNotLoadBeansEvenWithInvalidExtensionConfiguration() throws Exception {
    for (Class<?> type : List.of(
        org.example.traveljavaserver.knowledge.TravelEmbedding.class,
        org.example.traveljavaserver.knowledge.KnowledgeService.class,
        org.example.traveljavaserver.knowledge.KnowledgeStore.class,
        org.example.traveljavaserver.knowledge.RagEngine.class,
        org.example.traveljavaserver.knowledge.RagQuestions.class,
        org.example.traveljavaserver.knowledge.ChatKnowledge.class,
        org.example.traveljavaserver.controller.KnowledgeController.class)) {
      assertTrue(context.getBeansOfType(type).isEmpty(), type.getSimpleName());
    }
    assertEquals(404, a.send("GET", "/api/knowledge/documents", null).statusCode());
    assertEquals(0, models.get());
  }

  @Test
  void sameCityAliasIsAcceptedAndTrustedInputIsKept() throws Exception {
    mode = "city-alias";
    var plan = direct("浙江省杭州市", 1);
    assertEquals("浙江省杭州市", plan.path("city").asText());
    assertEquals(1, models.get());
    assertEquals(0, maps.get());
  }

  @Test
  void longPlansHaveOneModelCallWithLargerBoundedOutputAndInputLimitsRemain() throws Exception {
    for (int days : List.of(10, 30)) {
      var plan = direct("北京", days);
      assertEquals(days, plan.path("dailyItinerary").size());
      assertEquals(days == 10 ? 8200 : 16384, captured.path("max_tokens").asInt());
    }
    assertEquals(2, models.get());
    assertEquals(400, a.send("POST", "/api/travel/recommend", request("北京", 31)).statusCode());
    assertEquals(400, a.send("POST", "/api/travel/recommend",
        "{\"city\":\"北京\",\"days\":1,\"budget\":50}").statusCode());
    assertEquals(2, models.get());
    assertEquals(0, maps.get());
  }

  @Test
  void directPlanUsesOnlyModelAndCanSaveAndReloadWithoutMaps() throws Exception {
    var plan = direct("北京", 4);
    assertEquals(4, plan.path("dailyItinerary").size());
    assertTrue(plan.path("evidence").isNull());
    assertEquals("json_object", captured.path("response_format").path("type").asText());
    assertEquals(4096, captured.path("max_tokens").asInt());
    assertFalse(captured.path("stream").asBoolean());
    var saved = a.data("POST", "/api/trips", JSON.writeValueAsString(Map.of("plan", plan)));
    String id = saved.path("trip").path("id").asText();
    var b = new TestApi(port);
    b.login(a.username, TestApi.PASSWORD);
    assertEquals(saved, b.data("GET", "/api/trips/" + id, null));
    assertEquals(1, models.get());
    assertEquals(0, maps.get());
  }

  @Test
  void oldMapCreationAndRevisionEndpointsAreDisabledByDefault() throws Exception {
    for (String path :
        List.of("/api/planning/jobs", "/api/planning/trips/" + UUID.randomUUID() + "/revisions")) {
      String body =
          path.endsWith("jobs") ? request("杭州", 1) : "{\"baseVersion\":1,\"action\":\"refresh\"}";
      var r = a.send("POST", path, body);
      assertEquals(410, r.statusCode());
      assertEquals(
          "EXTERNAL_PLANNING_DISABLED", JSON.readTree(r.body()).path("errorCode").asText());
    }
    assertEquals(0, maps.get());
    assertEquals(0, models.get());
  }

  @Test
  void invalidOrInconsistentModelPlansAreRejectedWithoutSaving() throws Exception {
    for (String invalid :
        List.of("bad-json", "wrong-city", "wrong-days", "wrong-budget", "blank-spot")) {
      mode = invalid;
      var r = a.send("POST", "/api/travel/recommend", request("北京", 2));
      assertEquals(502, r.statusCode());
      assertEquals("INVALID_TRAVEL_PLAN", JSON.readTree(r.body()).path("errorCode").asText());
    }
    assertEquals(0, a.data("GET", "/api/trips", null).path("total").asInt());
    assertEquals(0, maps.get());
  }

  @Test
  void providerFailuresAndTruncationRemainExplicitWithoutMapFallback() throws Exception {
    for (String failure : List.of("auth", "rate", "truncated")) {
      mode = failure;
      var r = a.send("POST", "/api/travel/recommend", request("北京", 1));
      assertEquals(failure.equals("rate") ? 503 : 502, r.statusCode());
      assertFalse(r.body().contains("private-provider-detail"));
      if (failure.equals("truncated"))
        assertEquals("MODEL_OUTPUT_TRUNCATED", JSON.readTree(r.body()).path("errorCode").asText());
    }
    assertEquals(3, models.get());
    assertEquals(0, maps.get());
  }

  @Test
  void modelCannotForgeVerifiedMapEvidence() throws Exception {
    mode = "fake-evidence";
    var plan = direct("成都", 1);
    assertTrue(plan.path("evidence").isNull());
    assertTrue(plan.path("estimatedTotal").isNull());
    var slot = plan.path("dailyItinerary").get(0).path("morning");
    assertTrue(slot.path("placeId").isNull());
    assertTrue(slot.path("sourceUrl").isNull());
    assertEquals(0, maps.get());
  }

  @Test
  void invalidInputDoesNotCallAnyProvider() throws Exception {
    assertEquals(
        400,
        a.send("POST", "/api/travel/recommend", "{\"city\":\"\",\"days\":0,\"budget\":1}")
            .statusCode());
    assertEquals(0, models.get());
    assertEquals(0, maps.get());
  }

  @Test
  void cancellationBeforePostPreventsModelCallAndIsScopedToOwner() throws Exception {
    String id=UUID.randomUUID().toString();
    a.data("DELETE","/api/travel/recommend/"+id,null);
    var body=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(request("杭州",1));
    body.put("requestId",id);
    var response=a.send("POST","/api/travel/recommend",body.toString());
    assertEquals(409,response.statusCode());
    assertEquals("PLANNING_CANCELLED",JSON.readTree(response.body()).path("errorCode").asText());
    assertEquals(0,models.get());
    var b=new TestApi(port);b.registerLogin();
    assertEquals(200,b.send("POST","/api/travel/recommend",body.toString()).statusCode());
    assertEquals(1,models.get());
  }

  @Test
  void cancellingRunningPlanReleasesItsSlotAndPreservesSavedTrips() throws Exception {
    mode="slow";started=new CountDownLatch(1);
    String id=UUID.randomUUID().toString();
    var body=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(request("杭州",1));
    body.put("requestId",id);
    var work=a.client.sendAsync(a.request("POST","/api/travel/recommend",body.toString()),HttpResponse.BodyHandlers.ofString());
    assertTrue(started.await(3,TimeUnit.SECONDS));
    var b=new TestApi(port);b.registerLogin();
    b.data("DELETE","/api/travel/recommend/"+id,null);
    assertFalse(work.isDone());
    assertTrue(a.data("DELETE","/api/travel/recommend/"+id,null).path("cancelled").asBoolean());
    assertEquals(409,work.get(3,TimeUnit.SECONDS).statusCode());
    release.countDown();mode="ok";
    assertTrue(direct("杭州",1).path("success").asBoolean());
    assertEquals(0,a.data("GET","/api/trips",null).path("total").asInt());
  }

  @Test
  void concurrentDirectRequestsAreBounded() throws Exception {
    mode = "slow";
    var one =
        a.client.sendAsync(
            a.request("POST", "/api/travel/recommend", request("北京", 1)),
            HttpResponse.BodyHandlers.ofString());
    var two =
        a.client.sendAsync(
            a.request("POST", "/api/travel/recommend", request("上海", 1)),
            HttpResponse.BodyHandlers.ofString());
    try {
      assertTrue(started.await(3, TimeUnit.SECONDS));
      assertEquals(429, a.send("POST", "/api/travel/recommend", request("杭州", 1)).statusCode());
    } finally {
      release.countDown();
    }
    assertEquals(200, one.get(5, TimeUnit.SECONDS).statusCode());
    assertEquals(200, two.get(5, TimeUnit.SECONDS).statusCode());
    assertEquals(2, models.get());
    assertEquals(0, maps.get());
  }
}

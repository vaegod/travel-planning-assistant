package org.example.traveljavaserver.planning;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.example.traveljavaserver.TestApi;
import org.example.traveljavaserver.common.AppException;
import org.example.traveljavaserver.service.DataService;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlanningTests {
  @Test
  void diskCacheSurvivesRestartAndExpiredDataNeverHidesAnUpstreamFailure(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    String url = "http://127.0.0.1:" + provider.getAddress().getPort();
    var first = new TravelTools(url + "/places", url, directory.toString());
    var restarted = new TravelTools(url + "/places", url, directory.toString());
    var expired = new TravelTools(url + "/places", url, directory.toString());
    Work.CURRENT.set(
        new Work(
            1, new PlanTypes.Start("杭州", 1, new java.math.BigDecimal("1000")), null, null, 10000));
    try {
      var original = first.search("杭州");
      mode = "place-fail";
      var cached = restarted.search("杭州");
      assertTrue(cached.cached());
      assertEquals(original.queriedAt(), cached.queriedAt());
      assertEquals(1, placeCalls.get());
      var stale =
          new PlanTypes.Places(
              "杭州",
              original.items(),
              original.source(),
              java.time.Instant.now().minusSeconds(3601).toString(),
              false);
      java.nio.file.Files.writeString(directory.resolve("杭州.json"), JSON.writeValueAsString(stale));
      assertThrows(AppException.class, () -> expired.search("杭州"));
      assertEquals(2, placeCalls.get());
    } finally {
      Work.CURRENT.remove();
      first.close();
      restarted.close();
      expired.close();
    }
  }

  static final ObjectMapper JSON = new ObjectMapper();
  static final AtomicInteger placeCalls = new AtomicInteger(), modelCalls = new AtomicInteger();
  static volatile String mode = "ok";
  static final ExecutorService threads =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "planning-test-provider");
            t.setDaemon(true);
            return t;
          });
  static final HttpServer provider = provider();
  @LocalServerPort int port;
  @Autowired TravelTools tools;
  @Autowired PlanningJobs jobs;
  @Autowired DataService data;
  @Autowired PlanningEngine engine;

  @Test
  void timeoutOutcomeSurvivesLateProviderCancellation() throws Exception {
    mode = "slow";
    var bounded = new PlanningJobs(engine, data, 30);
    try {
      var job =
          bounded.create(a.userId, new PlanTypes.Start("杭州", 1, new java.math.BigDecimal("2000")));
      PlanTypes.View result = null;
      for (int i = 0; i < 150; i++) {
        result = bounded.get(a.userId, job.id());
        if (result.status().equals("ERROR")) break;
        Thread.sleep(10);
      }
      assertNotNull(result);
      assertEquals("PLANNING_TIMEOUT", result.errorCode());
      Thread.sleep(100);
      assertEquals("PLANNING_TIMEOUT", bounded.get(a.userId, job.id()).errorCode());
      assertNull(bounded.get(a.userId, job.id()).plan());
    } finally {
      bounded.close();
    }
  }

  TestApi a;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry props) {
    props.add("planning.external-enabled", () -> true);
    String url = "http://127.0.0.1:" + provider.getAddress().getPort();
    props.add("planning.overpass-url", () -> url + "/places");
    props.add("planning.osrm-url", () -> url);
    props.add("llm.base-url", () -> url);
    props.add("llm.api-key", () -> "test-key");
    props.add("llm.model", () -> "test-model");
  }

  static HttpServer provider() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(threads);
      server.createContext(
          "/places",
          exchange -> {
            placeCalls.incrementAndGet();
            if (mode.equals("slow")) {
              try {
                Thread.sleep(1500);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
            if (mode.equals("place-fail")) {
              reply(exchange, 503, Map.of("error", "unavailable"));
              return;
            }
            List<Object> places = new ArrayList<>();
            for (int i = 1; i <= 24; i++)
              places.add(
                  Map.of(
                      "type",
                      "node",
                      "id",
                      i,
                      "lat",
                      30.245 + i * .001,
                      "lon",
                      120.13 + i * .001,
                      "tags",
                      Map.of("name", "测试景点" + i, "tourism", i % 2 == 0 ? "museum" : "attraction")));
            reply(exchange, 200, Map.of("elements", places));
          });
      server.createContext(
          "/route",
          exchange -> {
            if (mode.equals("no-route")) {
              reply(exchange, 200, Map.of("code", "NoRoute"));
              return;
            }
            String coordinateText =
                exchange.getRequestURI().getPath().substring("/route/v1/driving/".length());
            int count = coordinateText.split(";").length;
            List<Object> legs = new ArrayList<>();
            for (int i = 1; i < count; i++)
              legs.add(
                  Map.of("distance", 500.0, "duration", mode.equals("too-long") ? 40000.0 : 300.0));
            reply(exchange, 200, Map.of("code", "Ok", "routes", List.of(Map.of("legs", legs))));
          });
      server.createContext(
          "/chat/completions",
          exchange -> {
            int call = modelCalls.incrementAndGet();
            var root = JSON.readTree(exchange.getRequestBody().readAllBytes());
            String prompt = root.path("messages").get(1).path("content").asText();
            int start = prompt.indexOf("候选：") + 3;
            JsonNode candidates = JSON.readTree(prompt.substring(start));
            var match = java.util.regex.Pattern.compile("请选择(\\d+)个").matcher(prompt);
            if (!match.find()) throw new IOException("No count");
            int count = Integer.parseInt(match.group(1));
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) ids.add(candidates.get(i).path("id").asText());
            if (mode.equals("invalid") || mode.equals("retry") && call == 1)
              ids = Collections.nCopies(count, "invented/id");
            String content = JSON.writeValueAsString(Map.of("placeIds", ids));
            reply(
                exchange,
                200,
                Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static void reply(HttpExchange exchange, int status, Object value) throws IOException {
    byte[] body = JSON.writeValueAsBytes(value);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length);
    try (var out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  @BeforeEach
  void setup() throws Exception {
    mode = "ok";
    tools.clearCache();
    placeCalls.set(0);
    modelCalls.set(0);
    a = new TestApi(port);
    a.registerLogin();
  }

  @AfterEach
  void cleanup() {
    jobs.cancelUser(a.userId);
  }

  @AfterAll
  static void close() {
    provider.stop(0);
    threads.shutdownNow();
  }

  JsonNode create(int days, int budget) throws Exception {
    return a.data(
        "POST",
        "/api/planning/jobs",
        JSON.writeValueAsString(Map.of("city", "杭州", "days", days, "budget", budget)));
  }

  JsonNode await(String id) throws Exception {
    for (int i = 0; i < 150; i++) {
      var value = a.data("GET", "/api/planning/jobs/" + id, null);
      if (!Set.of("RUNNING", "QUEUED").contains(value.path("status").asText())) return value;
      Thread.sleep(30);
    }
    throw new AssertionError("Planning did not finish");
  }

  JsonNode ready(int days, int budget) throws Exception {
    var job = await(create(days, budget).path("id").asText());
    assertEquals("READY", job.path("status").asText(), job.toString());
    return job;
  }

  JsonNode save(JsonNode job) throws Exception {
    return a.data("POST", "/api/planning/jobs/" + job.path("id").asText() + "/save", "{}");
  }

  JsonNode revision(String trip, int version, String action, int day, int budget) throws Exception {
    var value =
        a.data(
            "POST",
            "/api/planning/trips/" + trip + "/revisions",
            JSON.writeValueAsString(
                Map.of(
                    "baseVersion",
                    version,
                    "action",
                    action,
                    "day",
                    day,
                    "budget",
                    budget,
                    "instruction",
                    "只修改指定范围")));
    return await(value.path("id").asText());
  }

  @Test
  void realToolCallbacksProduceProvenanceAndCodeComputedBudget() throws Exception {
    var job = ready(3, 2000);
    var plan = job.path("plan");
    assertEquals(3, plan.path("dailyItinerary").size());
    assertEquals("validated", plan.path("evidence").path("status").asText());
    java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
    for (var amount : plan.path("budgetBreakdown")) sum = sum.add(amount.decimalValue());
    assertEquals(0, sum.compareTo(plan.path("estimatedTotal").decimalValue()));
    assertEquals(
        0,
        plan.path("totalBudget")
            .decimalValue()
            .subtract(sum)
            .compareTo(plan.path("remainingBudget").decimalValue()));
    assertTrue(
        plan.path("dailyItinerary").get(0).path("afternoon").path("route").path("meters").asDouble()
            > 0);
    assertTrue(plan.toString().contains("票价未知"));
    assertEquals(3, job.path("traces").size());
  }

  @Test
  void singleDayReplacementPreservesEveryOtherDayAndCreatesDiff() throws Exception {
    var original = save(ready(3, 2000));
    String trip = original.path("trip").path("id").asText();
    var job = revision(trip, 1, "indoor", 2, 2000);
    assertEquals("READY", job.path("status").asText(), job.toString());
    assertEquals(
        original.path("plan").path("dailyItinerary").get(0),
        job.path("plan").path("dailyItinerary").get(0));
    assertEquals(
        original.path("plan").path("dailyItinerary").get(2),
        job.path("plan").path("dailyItinerary").get(2));
    var day = job.path("plan").path("dailyItinerary").get(1);
    assertTrue(day.path("morning").path("indoor").asBoolean());
    assertTrue(day.path("afternoon").path("indoor").asBoolean());
    assertTrue(day.path("evening").path("rest").asBoolean());
    assertFalse(job.path("changes").isEmpty());
    for (var change : job.path("changes"))
      assertNotEquals(
          "totalBudget",
          change.path("path").asText(),
          "Equal amounts with different decimal scales must not produce a budget diff");
    assertEquals(
        1, a.data("GET", "/api/trips/" + trip, null).path("trip").path("currentVersion").asInt());
    assertEquals(2, save(job).path("trip").path("currentVersion").asInt());
  }

  @Test
  void budgetAndRelaxedRevisionsDoNotInventNewPlaces() throws Exception {
    var original = save(ready(2, 2000));
    String trip = original.path("trip").path("id").asText();
    var budget = revision(trip, 1, "budget", 1, 1500);
    assertEquals("READY", budget.path("status").asText());
    assertEquals(
        original.path("plan").path("dailyItinerary"), budget.path("plan").path("dailyItinerary"));
    assertEquals(1500, budget.path("plan").path("totalBudget").asInt());
    var relaxed = revision(trip, 1, "relaxed", 1, 2000);
    assertEquals("READY", relaxed.path("status").asText());
    assertTrue(
        relaxed
            .path("plan")
            .path("dailyItinerary")
            .get(0)
            .path("evening")
            .path("rest")
            .asBoolean());
    assertEquals(
        original.path("plan").path("dailyItinerary").get(1),
        relaxed.path("plan").path("dailyItinerary").get(1));
  }

  @Test
  void saveIsIdempotentAndStalePreviewCannotOverwriteNewVersion() throws Exception {
    var initialJob = ready(1, 2000);
    var original = save(initialJob);
    assertEquals(original.path("trip").path("id"), save(initialJob).path("trip").path("id"));
    String trip = original.path("trip").path("id").asText();
    var one = revision(trip, 1, "budget", 1, 1500);
    var two = revision(trip, 1, "budget", 1, 1800);
    save(one);
    var conflict = a.send("POST", "/api/planning/jobs/" + two.path("id").asText() + "/save", "{}");
    assertEquals(409, conflict.statusCode());
    assertEquals(2, a.data("GET", "/api/trips/" + trip, null).path("versions").size());
    assertEquals(
        2000,
        a.data("GET", "/api/trips/" + trip + "?version=1", null)
            .path("plan")
            .path("totalBudget")
            .asInt());
  }

  @Test
  void lowBudgetFailsWithoutChangingSavedPlan() throws Exception {
    var original = save(ready(2, 2000));
    String trip = original.path("trip").path("id").asText();
    var low = revision(trip, 1, "budget", 1, 100);
    assertEquals("ERROR", low.path("status").asText());
    assertEquals("BUDGET_TOO_LOW", low.path("errorCode").asText());
    assertEquals(
        409,
        a.send("POST", "/api/planning/jobs/" + low.path("id").asText() + "/save", "{}")
            .statusCode());
    assertEquals(
        1, a.data("GET", "/api/trips/" + trip, null).path("trip").path("currentVersion").asInt());
  }

  @Test
  void toolFailuresAndMissingRoutesNeverBecomeReady() throws Exception {
    mode = "place-fail";
    var failed = await(create(1, 2000).path("id").asText());
    assertEquals("ERROR", failed.path("status").asText());
    assertTrue(failed.path("plan").isNull());
    mode = "no-route";
    tools.clearCache();
    var route = await(create(1, 2000).path("id").asText());
    assertEquals("ERROR", route.path("status").asText());
    assertEquals("NO_ROUTE", route.path("errorCode").asText());
  }

  @Test
  void invalidModelSelectionGetsOnlyOneRepairAttempt() throws Exception {
    mode = "retry";
    ready(1, 2000);
    assertEquals(2, modelCalls.get());
    mode = "invalid";
    modelCalls.set(0);
    var failure = await(create(1, 2000).path("id").asText());
    assertEquals("INVALID_SELECTION", failure.path("errorCode").asText());
    assertEquals(2, modelCalls.get());
  }

  @Test
  void excessiveTravelTimeIsRejected() throws Exception {
    mode = "too-long";
    var failure = await(create(1, 2000).path("id").asText());
    assertEquals("DAY_TOO_LONG", failure.path("errorCode").asText());
  }

  @Test
  void otherUserCannotInspectCancelSaveOrReviseOwnedDraft() throws Exception {
    var job = ready(1, 2000);
    var b = new TestApi(port);
    b.registerLogin();
    String path = "/api/planning/jobs/" + job.path("id").asText();
    assertEquals(404, b.send("GET", path, null).statusCode());
    assertEquals(404, b.send("DELETE", path, null).statusCode());
    assertEquals(404, b.send("POST", path + "/save", "{}").statusCode());
    String trip = save(job).path("trip").path("id").asText();
    assertEquals(
        404,
        b.send(
                "POST",
                "/api/planning/trips/" + trip + "/revisions",
                "{\"baseVersion\":1,\"action\":\"refresh\"}")
            .statusCode());
  }

  @Test
  void cancelAndLogoutPreventSavingAndLimitConcurrentWork() throws Exception {
    mode = "slow";
    var first = create(1, 2000);
    var second = create(1, 2000);
    assertEquals(
        429,
        a.send("POST", "/api/planning/jobs", "{\"city\":\"杭州\",\"days\":1,\"budget\":2000}")
            .statusCode());
    a.data("DELETE", "/api/planning/jobs/" + first.path("id").asText(), null);
    assertEquals("CANCELLED", await(first.path("id").asText()).path("status").asText());
    a.data("POST", "/api/auth/logout", "{}");
    a.login(a.username, TestApi.PASSWORD);
    assertEquals("CANCELLED", await(second.path("id").asText()).path("status").asText());
    assertEquals(
        409,
        a.send("POST", "/api/planning/jobs/" + first.path("id").asText() + "/save", "{}")
            .statusCode());
  }

  @Test
  void provenanceCannotBeForgedThroughLegacySaveApi() throws Exception {
    var job = ready(1, 2000);
    var response =
        a.send("POST", "/api/trips", JSON.writeValueAsString(Map.of("plan", job.path("plan"))));
    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains("USE_PLANNING_JOB_SAVE"));
  }

  @Test
  void cachesKeepOriginalQueryTimeAndInvalidArgumentsAreRejected() throws Exception {
    var one = ready(1, 2000);
    var two = ready(1, 2000);
    assertEquals(1, placeCalls.get());
    assertEquals(
        one.path("plan").path("dailyItinerary").get(0).path("morning").path("queriedAt"),
        two.path("plan").path("dailyItinerary").get(0).path("morning").path("queriedAt"));
    assertEquals(
        400,
        a.send("POST", "/api/planning/jobs", "{\"city\":\"上海\",\"days\":1,\"budget\":1000}")
            .statusCode());
    assertEquals(
        400,
        a.send("POST", "/api/planning/jobs", "{\"city\":\"杭州\",\"days\":4,\"budget\":1000}")
            .statusCode());
  }

  @Test
  void deadlineAndCallBudgetAreBounded() {
    var expired =
        new Work(1, new PlanTypes.Start("杭州", 1, new java.math.BigDecimal("1000")), null, null, 0);
    assertEquals(
        "PLANNING_TIMEOUT", assertThrows(AppException.class, expired::remaining).getErrorCode());
    var work =
        new Work(
            1, new PlanTypes.Start("杭州", 1, new java.math.BigDecimal("1000")), null, null, 1000);
    for (int i = 0; i < 8; i++) work.use();
    assertEquals("TOOL_LIMIT", assertThrows(AppException.class, work::use).getErrorCode());
  }

  @Test
  void legacyPlansRequireExplicitRefreshBeforeLocalRevisions() throws Exception {
    var source = JSON.treeToValue(ready(1, 2000).path("plan"), TravelRecommendVO.class);
    source.setEvidence(null);
    var original = a.data("POST", "/api/trips", JSON.writeValueAsString(Map.of("plan", source)));
    String trip = original.path("trip").path("id").asText();
    assertEquals("LEGACY_PLAN", revision(trip, 1, "indoor", 1, 2000).path("errorCode").asText());
    var refreshed = revision(trip, 1, "refresh", 1, 2000);
    assertEquals("READY", refreshed.path("status").asText());
    assertEquals(2, save(refreshed).path("trip").path("currentVersion").asInt());
    assertTrue(
        a.data("GET", "/api/trips/" + trip + "?version=1", null)
            .path("plan")
            .path("evidence")
            .isNull());
  }

  @Test
  void constraintsRejectDuplicatePlacesInvalidCoordinatesAndHugeRouteDurations() throws Exception {
    var valid = JSON.treeToValue(ready(1, 2000).path("plan"), TravelRecommendVO.class);
    var duplicate = PlanRules.copy(valid);
    var day = duplicate.getDailyItinerary().get(0);
    day.getAfternoon().setPlaceId(day.getMorning().getPlaceId());
    assertEquals(
        "DUPLICATE_PLACE",
        assertThrows(AppException.class, () -> PlanRules.calculate(duplicate)).getErrorCode());
    var invalid = PlanRules.copy(valid);
    invalid.getDailyItinerary().get(0).getMorning().setLatitude(Double.NaN);
    assertEquals(
        "INVALID_PLAN",
        assertThrows(AppException.class, () -> PlanRules.calculate(invalid)).getErrorCode());
    var huge = PlanRules.copy(valid);
    var slot = huge.getDailyItinerary().get(0).getAfternoon();
    var old = slot.getRoute();
    slot.setRoute(
        new PlanTypes.Leg(
            old.fromId(),
            old.toId(),
            old.meters(),
            Double.MAX_VALUE,
            old.profile(),
            old.source(),
            old.queriedAt(),
            old.estimatedFare()));
    assertEquals(
        "DAY_TOO_LONG",
        assertThrows(AppException.class, () -> PlanRules.calculate(huge)).getErrorCode());
  }
}

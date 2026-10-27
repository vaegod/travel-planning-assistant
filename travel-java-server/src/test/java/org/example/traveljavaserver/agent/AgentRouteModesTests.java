package org.example.traveljavaserver.agent;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.math.BigDecimal;
import java.util.*;
import org.example.traveljavaserver.TestApi;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.junit.jupiter.api.*;

class AgentRouteModesTests {
  HttpServer server;
  AgentMaps maps;
  String lastPath, lastQuery, responseMode = "ok";
  int walkingCalls;
  @BeforeEach void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v3/direction", e -> {
      lastPath = e.getRequestURI().getPath(); lastQuery = e.getRequestURI().getQuery();
      Object body;
      if (lastPath.endsWith("/walking")) {
        walkingCalls++;
        body = Map.of("status", "1", "route", Map.of("paths", List.of(Map.of("duration", "505", "distance", "631"))));
      } else if (lastPath.endsWith("/driving")) {
        body = Map.of("status", "1", "route", Map.of("paths", List.of(Map.of("duration", "180", "distance", "950", "tolls", "2"))));
      } else {
        var line = Map.of("name", "地铁1号线", "distance", "4000",
            "departure_stop", Map.of("name", "起点站"), "arrival_stop", Map.of("name", "终点站"));
        var transit = new LinkedHashMap<String, Object>();
        transit.put("duration", responseMode.equals("bad") ? List.of() : "900");
        transit.put("cost", responseMode.equals("unknown_fare") ? List.of() : responseMode.equals("expensive") ? "50" : "5");
        transit.put("segments", List.of(Map.of("walking", Map.of("distance", "100"),
            "bus", Map.of("buslines", List.of(line, Map.of("name", "另一线路", "distance", "9999")))),
            Map.of("walking", Map.of("distance", "200"), "bus", Map.of("buslines", List.of()))));
        if (responseMode.equals("walk_only"))
          transit.put("segments", List.of(Map.of("walking", Map.of("distance", "4500"))));
        if (!Set.of("sum", "unknown_fare").contains(responseMode)) transit.put("distance", "4500");
        body = Map.of("status", "1", "route", Map.of("distance", "999",
            "paths", List.of(Map.of("duration", "100", "distance", "999")),
            "transits", responseMode.equals("empty") ? List.of() : List.of(transit)));
      }
      byte[] bytes = TestApi.JSON.writeValueAsBytes(body);
      e.sendResponseHeaders(200, bytes.length);
      try (var out = e.getResponseBody()) { out.write(bytes); }
    });
    server.start(); maps = new AgentMaps("fixture-key", "http://127.0.0.1:" + server.getAddress().getPort());
  }
  @AfterEach void close() { maps.close(); server.stop(0); }
  AgentRun run(String mode) {
    return new AgentRun(1, new AgentTypes.Start(UUID.randomUUID().toString(), "杭州", 1,
        new BigDecimal("1000"), "", mode), null, null, null, "test", 10000);
  }
  AgentMaps.Place from() { return new AgentMaps.Place("A", "起点", "", 30.12345678, 120.12345678, "test-time"); }
  AgentMaps.Place to() { return new AgentMaps.Place("B", "终点", "", 30.2, 120.2, "test-time"); }
  @Test void drivingUsesDrivingEndpointAndPreservesUnknownTotalCost() {
    var route = maps.route(run("driving"), from(), to());
    assertEquals("/v3/direction/driving", lastPath); assertTrue(lastQuery.contains("originid=A"));
    assertTrue(lastQuery.contains("origin=120.123457,30.123457"));
    assertEquals("driving", route.profile()); assertEquals(950, route.meters()); assertEquals(180, route.seconds());
    assertNull(route.estimatedFare()); assertTrue(route.fareNotice().contains("2元"));
    assertTrue(route.fareNotice().contains("油费")); assertEquals(0, walkingCalls);
  }
  @Test void transitCarriesCityAndParsesItineraryInsteadOfTopLevelDistance() {
    var route = maps.route(run("transit"), from(), to());
    assertEquals("/v3/direction/transit/integrated", lastPath);
    assertTrue(lastQuery.contains("city=杭州")); assertTrue(lastQuery.contains("cityd=杭州"));
    assertEquals("transit", route.profile()); assertEquals(4500, route.meters());
    assertEquals(15, route.minutes()); assertEquals(new BigDecimal("5"), route.estimatedFare());
    assertTrue(route.summary().contains("地铁1号线（起点站→终点站）")); assertTrue(route.summary().contains("步行100米"));
  }
  @Test void absentTransitTotalSumsWalkingAndOneLinePerSegment() {
    responseMode = "sum";
    var route = maps.route(run("transit"), from(), to());
    assertEquals(4300, route.meters()); assertEquals(0, walkingCalls);
  }
  @Test void unknownTransitFareRemainsUnknown() {
    responseMode = "unknown_fare";
    assertNull(maps.route(run("transit"), from(), to()).estimatedFare());
  }
  @Test void emptyOrMalformedTransitCannotFallBackToWalking() {
    for (String mode : List.of("empty", "bad", "walk_only")) {
      responseMode = mode;
      var error = assertThrows(IllegalArgumentException.class, () -> maps.route(run("transit"), from(), to()));
      assertTrue(error.getMessage().contains("公交"));
    }
    assertEquals(0, walkingCalls);
  }
  AgentTools tools(AgentRun run) {
    var tools = new AgentTools(run, maps);
    tools.places.put("A", from()); tools.places.put("B", to()); tools.route("A", "B");
    tools.estimate(new BigDecimal("200"), new BigDecimal("100"), new BigDecimal("40"),
        new BigDecimal("60"), new BigDecimal("20"));
    return tools;
  }
  TravelRecommendVO candidate() {
    var plan = AgentFixtures.mappedPlan();
    plan.getDailyItinerary().get(0).getMorning().setPlaceId("A");
    plan.getDailyItinerary().get(0).getAfternoon().setPlaceId("B");
    return plan;
  }
  @Test void serverBindsSelectedModeEvenWhenModelReturnsWalkingText() throws Exception {
    var tools = tools(run("driving"));
    var plan = candidate(); plan.getDailyItinerary().get(0).getAfternoon().setTransportation("步行1分钟");
    tools.validate(AgentRules.JSON.writeValueAsString(plan));
    assertNotNull(tools.accepted);
    var saved = tools.accepted.getDailyItinerary().get(0).getAfternoon();
    assertEquals("driving", tools.accepted.getTravelMode()); assertEquals("driving", saved.getRoute().profile());
    assertTrue(saved.getTransportation().startsWith("高德自驾"));
    assertEquals(3, saved.getTravelMinutes()); assertNull(saved.getRoute().estimatedFare());
  }
  @Test void transitFareIsIncludedInBudgetValidation() throws Exception {
    responseMode = "expensive";
    var run = run("transit"); var tools = tools(run); var plan = candidate();
    tools.validate(AgentRules.JSON.writeValueAsString(plan)); assertNull(tools.accepted);
    assertTrue(run.report.issues().stream().anyMatch(s -> s.contains("交通预算")));
    plan.getBudgetBreakdown().setTransportation(new BigDecimal("55"));
    tools.validate(AgentRules.JSON.writeValueAsString(plan)); assertNotNull(tools.accepted);
    assertEquals(new BigDecimal("50"), tools.accepted.getDailyItinerary().get(0).getAfternoon().getRoute().estimatedFare());
  }
}


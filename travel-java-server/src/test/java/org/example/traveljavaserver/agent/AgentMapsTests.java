package org.example.traveljavaserver.agent;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.example.traveljavaserver.TestApi;

class AgentMapsTests {
  HttpServer server;
  AgentMaps maps;
  String query;
  String mode="ok";
  @BeforeEach void start()throws Exception{
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v3/place/text",e->{
      query=e.getRequestURI().getQuery();
      send(e,mode.equals("error") ? Map.of("status","0","info","INVALID_KEY") :
          Map.of("status","1","pois",List.of(
              Map.of("id","A","name","测试公园","address","杭州","location","120.1,30.2"),
              Map.of("id","B","name","测试博物馆","address","杭州","location","120.11,30.21"))));
    });
    server.createContext("/v3/direction/walking",e->send(e,Map.of("status","1",
        "route",Map.of("paths",List.of(Map.of("duration","505","distance","631"))))));
    server.start();
    maps=new AgentMaps("test-only-map-key","http://127.0.0.1:"+server.getAddress().getPort());
  }
  void send(HttpExchange e,Object value)throws java.io.IOException{
    byte[] bytes=TestApi.JSON.writeValueAsBytes(value);e.sendResponseHeaders(200,bytes.length);
    try(var out=e.getResponseBody()){out.write(bytes);}
  }
  @AfterEach void stop(){maps.close();server.stop(0);}
  @Test void bindsQueriedPlacesToTaskCityAndRetainsServerProvenance()throws Exception{
    var run=AgentFixtures.mappedRun("walking");var tools=new AgentTools(run,maps);
    tools.search("公园");
    assertTrue(query.contains("city=杭州")&&query.contains("citylimit=true"));
    var p=AgentFixtures.mappedPlan();var s=p.getDailyItinerary().get(0).getMorning();
    s.setPlaceId("A");s.setSourceUrl("https://fake.invalid");s.setQueriedAt("fake");s.setLatitude(99.0);
    tools.estimate(new java.math.BigDecimal("200"),new java.math.BigDecimal("100"),
        new java.math.BigDecimal("40"),new java.math.BigDecimal("60"),new java.math.BigDecimal("20"));
    tools.validate(AgentRules.JSON.writeValueAsString(p));assertNotNull(tools.accepted);
    var accepted=tools.accepted.getDailyItinerary().get(0).getMorning();
    assertEquals("测试公园",accepted.getSpot());assertEquals(30.2,accepted.getLatitude());
    assertEquals("https://www.amap.com/detail/A",accepted.getSourceUrl());assertNotEquals("fake",accepted.getQueriedAt());
  }
  @Test void routesOnlyUsePlacesReturnedInThisTask(){
    var tools=new AgentTools(AgentFixtures.run(null),maps);
    assertThrows(IllegalArgumentException.class,()->tools.route("A","B"));
    tools.search("公园");
    var route=(AgentMaps.Route)tools.route("A","B");assertEquals(9,route.minutes());
    assertEquals(505,route.seconds());
    assertThrows(IllegalArgumentException.class,()->tools.route("A","X"));
  }
  @Test void upstreamFailureDoesNotProduceCandidates(){
    mode="error";var tools=new AgentTools(AgentFixtures.run(null),maps);
    assertThrows(IllegalArgumentException.class,()->tools.search("公园"));assertTrue(tools.places.isEmpty());
  }
  @Test void uniqueQueriedNamesRestoreOmittedIdsAndExactRouteDuration()throws Exception{
    var run=AgentFixtures.mappedRun("walking");var tools=new AgentTools(run,maps);
    tools.search("公园");tools.route("A","B");
    tools.estimate(new java.math.BigDecimal("200"),new java.math.BigDecimal("100"),
        new java.math.BigDecimal("40"),new java.math.BigDecimal("60"),new java.math.BigDecimal("20"));
    var plan=AgentFixtures.mappedPlan();var day=plan.getDailyItinerary().get(0);
    day.getMorning().setSpot("测试公园");day.getAfternoon().setSpot("测试博物馆");
    tools.validate(AgentRules.JSON.writeValueAsString(plan));
    assertNotNull(tools.accepted);
    var accepted=tools.accepted.getDailyItinerary().get(0);
    assertEquals("A",accepted.getMorning().getPlaceId());
    assertEquals("B",accepted.getAfternoon().getPlaceId());
    assertEquals(9,accepted.getAfternoon().getTravelMinutes());
    assertEquals(505,accepted.getAfternoon().getRoute().seconds());
    assertEquals(631,accepted.getAfternoon().getRoute().meters());
  }
  @Test void ambiguousOrPartialNamesCannotAcquireQueriedProvenance()throws Exception{
    var run=AgentFixtures.mappedRun("walking");var tools=new AgentTools(run,maps);
    tools.search("公园");
    var a=tools.places.get("A");
    tools.places.put("C",new AgentMaps.Place("C",a.name(),"另一个地址",30.3,120.2,a.queriedAt()));
    tools.estimate(new java.math.BigDecimal("200"),new java.math.BigDecimal("100"),
        new java.math.BigDecimal("40"),new java.math.BigDecimal("60"),new java.math.BigDecimal("20"));
    var plan=AgentFixtures.mappedPlan();var day=plan.getDailyItinerary().get(0);
    day.getMorning().setSpot("测试公园");day.getMorning().setPlaceId(null);
    day.getAfternoon().setSpot("博物馆");day.getAfternoon().setPlaceId(null);
    tools.validate(AgentRules.JSON.writeValueAsString(plan));assertNull(tools.accepted);
    assertTrue(run.report.issues().stream().anyMatch(s->s.contains("无法唯一确认")||s.contains("多个地点")));
  }
}


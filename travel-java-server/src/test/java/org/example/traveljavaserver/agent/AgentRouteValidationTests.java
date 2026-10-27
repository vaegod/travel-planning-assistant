package org.example.traveljavaserver.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import org.example.traveljavaserver.planning.PlanTypes;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.junit.jupiter.api.*;

class AgentRouteValidationTests {
  static class Maps extends AgentMaps {
    int searches, routes; boolean unavailable, failed;
    double seconds=505; BigDecimal fare=BigDecimal.ZERO;
    final List<String> edges=new ArrayList<>();
    Maps(){super("fixture","http://127.0.0.1");}
    @Override List<Place> search(AgentRun run,String keyword){
      searches++;
      if(failed)throw new ServiceFailure("地图服务暂不可用");
      return List.of(place("A","测试公园"),place("B","测试博物馆"),place("C","新景点"),place("D","晚间景点"));
    }
    Place place(String id,String name){return new Place(id,name,"",30,120+id.charAt(0)/1000.0,"fresh-query");}
    @Override Route route(AgentRun run,Place from,Place to){
      routes++;edges.add(from.id()+"->"+to.id());
      if(unavailable)throw new NoRoute("公交无可用方案："+from.name()+"→"+to.name());
      double time=from.id().equals("C")?1201:seconds;
      return new Route(from.id(),to.id(),(int)Math.ceil(time/60),900,time,run.start.travelMode(),"",fare,"参考费用","fresh-query");
    }
  }
  Maps maps;
  @BeforeEach void setup(){maps=new Maps();}
  @AfterEach void cleanup(){maps.close();}
  AgentTools tools(AgentRun run){
    var tools=new AgentTools(run,maps);
    tools.estimate(new BigDecimal("200"),new BigDecimal("100"),new BigDecimal("40"),new BigDecimal("20"),new BigDecimal("20"));
    return tools;
  }
  AgentRun revision(TravelRecommendVO base,String slot){
    var request=new AgentTypes.Revise(UUID.randomUUID().toString(),1,1,slot,"换成新景点");
    return new AgentRun(1,new AgentTypes.Start(request.requestId(),"杭州",base.getDays(),base.getTotalBudget(),request.instruction(),base.getTravelMode()),
        request,"trip",base,"revision",10000);
  }
  @Test void queriesMissingPlacesAndRoutesBeforeAcceptingAndRoundsSecondsUp()throws Exception{
    var run=AgentFixtures.mappedRun("walking");var tools=tools(run);var plan=AgentFixtures.mappedPlan();
    plan.getDailyItinerary().get(0).getAfternoon().setTravelMinutes(0);
    tools.validate(AgentRules.JSON.writeValueAsString(plan));
    assertNotNull(tools.accepted,String.valueOf(run.report));
    assertEquals(1,maps.searches);assertEquals(1,maps.routes);
    assertEquals(9,tools.accepted.getDailyItinerary().get(0).getAfternoon().getTravelMinutes());
    assertEquals(505,tools.accepted.getDailyItinerary().get(0).getAfternoon().getRoute().seconds());
    tools.validate(AgentRules.JSON.writeValueAsString(plan));assertEquals(1,maps.routes,"same route should be reused within the task");
  }
  @Test void referenceTimeCanMakeApparentlyShortDayExceedDailyLimit()throws Exception{
    maps.seconds=3600;var run=AgentFixtures.mappedRun("walking");var tools=tools(run);var plan=AgentFixtures.mappedPlan();
    var day=plan.getDailyItinerary().get(0);day.getMorning().setVisitMinutes(300);day.getAfternoon().setVisitMinutes(240);
    day.getEvening().setVisitMinutes(30);day.getAfternoon().setTravelMinutes(0);day.getMorning().setTravelMinutes(0);
    tools.validate(AgentRules.JSON.writeValueAsString(plan));assertNull(tools.accepted);
    assertTrue(run.report.issues().stream().anyMatch(i->i.contains("600")));
  }
  @Test void independentRulesRejectLowerMinutesAndStaleRouteEndpoints(){
    var run=AgentFixtures.mappedRun("walking");var plan=AgentFixtures.mappedPlan();var slot=plan.getDailyItinerary().get(0).getAfternoon();
    slot.setTravelMinutes(8);slot.setRoute(new PlanTypes.Leg("old","B",631,505,"walking","map","time",BigDecimal.ZERO));
    var issues=AgentRules.validate(run,plan).issues();
    assertTrue(issues.stream().anyMatch(i->i.contains("不得低于")));
    assertTrue(issues.stream().anyMatch(i->i.contains("起终点")));
  }
  @Test void replacingMorningRecalculatesIncomingAfternoonLegButPreservesItsActivity()throws Exception{
    var base=AgentFixtures.mappedPlan();var day=base.getDailyItinerary().get(0);
    day.getAfternoon().setRoute(new PlanTypes.Leg("A","B",631,505,"walking","old-map","old-time",BigDecimal.ZERO));
    day.getAfternoon().setTravelMinutes(9);
    var original=AgentRules.JSON.valueToTree(base);var run=revision(base,"morning");var tools=tools(run);
    var replacement=AgentFixtures.slot("新景点");replacement.setEstimatedCost(new BigDecimal("30"));
    tools.validate(AgentRules.JSON.writeValueAsString(Map.of("morning",replacement)));
    assertNotNull(tools.accepted,String.valueOf(run.report));
    var after=tools.accepted.getDailyItinerary().get(0).getAfternoon();
    assertEquals("C",after.getRoute().fromId());assertEquals("B",after.getRoute().toId());assertEquals(21,after.getTravelMinutes());
    assertEquals(day.getAfternoon().getSpot(),after.getSpot());assertEquals(day.getAfternoon().getDescription(),after.getDescription());
    assertEquals(day.getAfternoon().getDuration(),after.getDuration());assertEquals(day.getAfternoon().getQueriedAt(),after.getQueriedAt());
    assertEquals(new BigDecimal("40.00"),tools.accepted.getBudgetBreakdown().getTickets());
    assertEquals(base.getBudgetBreakdown().getAccommodation(),tools.accepted.getBudgetBreakdown().getAccommodation());
    assertEquals(original,AgentRules.JSON.valueToTree(base));
  }
  @Test void replacingAfternoonRecomputesBothAdjacentLegsIncludingUnselectedEvening()throws Exception{
    var base=AgentFixtures.mappedPlan();var day=base.getDailyItinerary().get(0);
    var evening=AgentFixtures.slot("晚间景点");evening.setPlaceId("D");
    evening.setRoute(new PlanTypes.Leg("B","D",600,500,"walking","old-map","old",BigDecimal.ZERO));day.setEvening(evening);
    base.getBudgetBreakdown().setTickets(new BigDecimal("30"));
    var run=revision(base,"afternoon");var tools=tools(run);
    tools.validate(AgentRules.JSON.writeValueAsString(Map.of("afternoon",AgentFixtures.slot("新景点"))));
    assertNotNull(tools.accepted,String.valueOf(run.report));
    var after=tools.accepted.getDailyItinerary().get(0);
    assertEquals("A",after.getAfternoon().getRoute().fromId());assertEquals("C",after.getAfternoon().getRoute().toId());
    assertEquals("C",after.getEvening().getRoute().fromId());assertEquals("D",after.getEvening().getRoute().toId());
    assertEquals("晚间景点",after.getEvening().getSpot());assertEquals(evening.getDescription(),after.getEvening().getDescription());
    assertEquals(day.getMorning(),after.getMorning());
  }
  @Test void unavailableTransitCannotBecomeZeroTimeDraftAndIsCached()throws Exception{
    maps.unavailable=true;var run=AgentFixtures.mappedRun("transit");var tools=tools(run);var plan=AgentFixtures.mappedPlan();
    tools.validate(AgentRules.JSON.writeValueAsString(plan));assertNull(tools.accepted);
    assertEquals("ROUTE_UNAVAILABLE",tools.blockedRoute.code());assertTrue(run.report.issues().get(0).contains("公交"));
    tools.validate(AgentRules.JSON.writeValueAsString(plan));assertEquals(1,maps.routes);
  }
  @Test void serviceFailureIsNotEvidenceThatNoTransitExists()throws Exception{
    maps.failed=true;var run=AgentFixtures.mappedRun("transit");var tools=tools(run);
    tools.validate(AgentRules.JSON.writeValueAsString(AgentFixtures.mappedPlan()));assertNull(tools.accepted);
    assertNull(tools.blockedRoute);assertTrue(run.report.issues().get(0).contains("服务"));
    var failure=assertThrows(org.example.traveljavaserver.common.AppException.class,()->tools.refuse("没有公交"));
    assertFalse(failure instanceof AgentDeclined);assertEquals("MAP_SERVICE_UNAVAILABLE",failure.getErrorCode());
  }
  @Test void routeProofIsRequiredEvenIfCallerSkipsTheSanitizer(){
    var run=AgentFixtures.mappedRun("walking");tools(run);var plan=AgentFixtures.mappedPlan();
    plan.getDailyItinerary().get(0).getAfternoon().setRoute(new PlanTypes.Leg("A","B",631,505,"walking","claimed","time",BigDecimal.ZERO));
    assertTrue(AgentRules.validate(run,plan).issues().stream().anyMatch(i->i.contains("必须查询核实")));
  }
  @Test void otherDatesAndFixedExpensesRemainByteEquivalentAfterFreshRouteQueries()throws Exception{
    var base=AgentFixtures.mappedPlan();base.setDays(2);
    base.getDailyItinerary().add(AgentFixtures.plan().getDailyItinerary().get(1));base.getBudgetBreakdown().setTickets(new BigDecimal("50"));
    var before=AgentRules.JSON.valueToTree(base);var run=revision(base,"morning");var tools=tools(run);
    tools.validate(AgentRules.JSON.writeValueAsString(Map.of("morning",AgentFixtures.slot("新景点"))));
    assertNotNull(tools.accepted,String.valueOf(run.report));
    assertEquals(before.path("dailyItinerary").path(1),AgentRules.JSON.valueToTree(tools.accepted).path("dailyItinerary").path(1));
    assertEquals(before,AgentRules.JSON.valueToTree(base));assertEquals(List.of("C->B"),maps.edges);
  }
  @Test void historicalProviderIdsDoNotPreventExactNameRouteReverification()throws Exception{
    var base=AgentFixtures.mappedPlan();var original=base.getDailyItinerary().get(0).getAfternoon();
    original.setPlaceId("legacy-provider-B");original.setSourceUrl("https://legacy.invalid/B");
    var run=revision(base,"morning");var tools=tools(run);
    tools.validate(AgentRules.JSON.writeValueAsString(Map.of("morning",AgentFixtures.slot("新景点"))));
    assertNotNull(tools.accepted,String.valueOf(run.report));
    var next=tools.accepted.getDailyItinerary().get(0).getAfternoon();
    assertEquals("legacy-provider-B",next.getPlaceId());assertEquals(original.getSourceUrl(),next.getSourceUrl());
    assertEquals("B",next.getRoute().toId());assertEquals("C",next.getRoute().fromId());
  }
}

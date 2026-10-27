package org.example.traveljavaserver.agent;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentRulesTests {
  @Test void validPlanHasComputedBudget() {
    var report = AgentRules.validate(AgentFixtures.run(null), AgentFixtures.plan());
    assertTrue(report.valid(), report.issues().toString()); assertEquals(new BigDecimal("500.00"),report.estimatedTotal());
    assertEquals(new BigDecimal("500.00"),report.remainingBudget());
  }
  @Test void rejectsOverspend() {
    var p=AgentFixtures.plan(); p.getBudgetBreakdown().setFood(new BigDecimal("900"));
    assertFalse(AgentRules.validate(AgentFixtures.run(null),p).valid());
  }
  @Test void rejectsDuplicateAndExcessiveDay() {
    var p=AgentFixtures.plan(); var day=p.getDailyItinerary().get(0);
    day.getAfternoon().setSpot(day.getMorning().getSpot());
    day.getMorning().setVisitMinutes(360); day.getAfternoon().setVisitMinutes(360);
    var issues=AgentRules.validate(AgentFixtures.run(null),p).issues();
    assertTrue(issues.stream().anyMatch(s->s.contains("重复")));
    assertTrue(issues.stream().anyMatch(s->s.contains("600")));
  }
  @Test void rejectsMissingSlotsOrWrongIdentity() {
    var p=AgentFixtures.plan(); p.setCity("北京");
    assertFalse(AgentRules.validate(AgentFixtures.run(null),p).valid());
    p.getDailyItinerary().get(0).setMorning(null);
    assertFalse(AgentRules.validate(AgentFixtures.run(null),p).valid());
  }
  @Test void rejectsNegativePrecisionAndTicketMismatch() {
    for(String cost:List.of("-1","1.001","70")){
      var p=AgentFixtures.plan(); p.getBudgetBreakdown().setTickets(new BigDecimal(cost));
      assertFalse(AgentRules.validate(AgentFixtures.run(null),p).valid());
    }
  }
  @Test void failedValidationClearsPriorAcceptedDraft() throws Exception {
    var tools=AgentFixtures.tools(AgentFixtures.run(null));
    tools.validate(AgentRules.JSON.writeValueAsString(AgentFixtures.plan())); assertNotNull(tools.accepted);
    tools.validate("{broken"); assertNull(tools.accepted);
  }
  @Test void localPatchPreservesEveryOtherSlotAndOriginalPlan() throws Exception {
    var revision=new AgentTypes.Revise(UUID.randomUUID().toString(),1,2,"afternoon","换博物馆");
    var run=AgentFixtures.run(revision); var before=AgentRules.JSON.valueToTree(run.base);
    var tools=AgentFixtures.tools(run);
    tools.validate(AgentRules.JSON.writeValueAsString(Map.of("afternoon",AgentFixtures.slot("新博物馆"))));
    assertNotNull(tools.accepted, String.valueOf(run.report));
    assertEquals(before,AgentRules.JSON.valueToTree(run.base));
    var result=AgentRules.JSON.valueToTree(tools.accepted);
    assertEquals(before.path("dailyItinerary").path(0),result.path("dailyItinerary").path(0));
    assertEquals(before.path("dailyItinerary").path(1).path("morning"),result.path("dailyItinerary").path(1).path("morning"));
    assertEquals(before.path("dailyItinerary").path(1).path("evening"),result.path("dailyItinerary").path(1).path("evening"));
    assertEquals("新博物馆",result.path("dailyItinerary").path(1).path("afternoon").path("spot").asText());
    assertEquals(1,AgentRules.diff(run.base,tools.accepted).size());
  }
  @Test void refusesOutOfScopePatchAndAccommodationChanges() throws Exception {
    var run=AgentFixtures.run(new AgentTypes.Revise(UUID.randomUUID().toString(),1,1,"afternoon","调整"));
    assertThrows(IllegalArgumentException.class,()->AgentRules.candidate(run,"{\"morning\":{}}"));
    var costs=AgentFixtures.plan().getBudgetBreakdown(); costs.setAccommodation(BigDecimal.ZERO);
    assertThrows(IllegalArgumentException.class,()->AgentRules.candidate(run,AgentRules.JSON.writeValueAsString(
        Map.of("afternoon",AgentFixtures.slot("新景点"),"budgetBreakdown",costs))));
  }
  @Test void legacyUntouchedSlotsNeedNotBeRewritten() throws Exception {
    var run=AgentFixtures.run(new AgentTypes.Revise(UUID.randomUUID().toString(),1,1,"afternoon","调整"));
    run.base.getDailyItinerary().get(1).getMorning().setVisitMinutes(null);
    var tools=AgentFixtures.tools(run); tools.validate(AgentRules.JSON.writeValueAsString(Map.of("afternoon",AgentFixtures.slot("新景点"))));
    assertNotNull(tools.accepted);
    assertNull(tools.accepted.getDailyItinerary().get(1).getMorning().getVisitMinutes());
    assertTrue(run.report.warnings().stream().anyMatch(s->s.contains("缺少时长")));
  }
  @Test void stripsUnqueriedSourceAndEvidence() throws Exception {
    var p=AgentFixtures.plan(); var slot=p.getDailyItinerary().get(0).getMorning();
    slot.setPlaceId("fake"); slot.setSourceUrl("https://example.com"); slot.setLatitude(1.0); slot.setQueriedAt("fake");
    var tools=AgentFixtures.tools(AgentFixtures.run(null)); tools.validate(AgentRules.JSON.writeValueAsString(p));
    assertNotNull(tools.accepted); var accepted=tools.accepted.getDailyItinerary().get(0).getMorning();
    assertNull(accepted.getPlaceId()); assertNull(accepted.getSourceUrl()); assertNull(accepted.getLatitude());
  }
  @Test void estimationIsRequiredAndMapFailureDoesNotInventResults() throws Exception {
    var tools=new AgentTools(AgentFixtures.run(null),new AgentMaps("","https://restapi.amap.com"));
    tools.validate(AgentRules.JSON.writeValueAsString(AgentFixtures.plan())); assertNull(tools.accepted);
    assertThrows(IllegalArgumentException.class,()->tools.search("公园"));
  }
}


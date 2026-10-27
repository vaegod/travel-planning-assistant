package org.example.traveljavaserver.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentDraftInputTests {
  @Test void directObjectSubmissionUsesTheSameStrictValidator() {
    var run=AgentFixtures.run(null);var tools=AgentFixtures.tools(run);
    @SuppressWarnings("unchecked") var draft=(Map<String,Object>)AgentRules.JSON.convertValue(AgentFixtures.plan(),Map.class);
    tools.submit(draft);
    assertNotNull(tools.accepted);assertTrue(run.report.valid());
  }
  @Test void malformedNestedJsonExplainsLocationAndDoesNotAcceptTrailingObjects()throws Exception {
    var run=AgentFixtures.run(null);var tools=AgentFixtures.tools(run);
    tools.validate(AgentRules.JSON.writeValueAsString(AgentFixtures.plan())+"}");
    assertNull(tools.accepted);assertFalse(run.report.valid());
    assertTrue(run.report.issues().get(0).contains("JSON语法错误"));
    assertTrue(run.report.issues().get(0).contains("列"));
  }
  @Test void wrongFieldTypeExplainsTheFieldWithoutDumpingDraftOrJavaTypes()throws Exception {
    var run=AgentFixtures.run(null);var tools=AgentFixtures.tools(run);
    var draft=AgentRules.JSON.valueToTree(AgentFixtures.plan());
    ((com.fasterxml.jackson.databind.node.ObjectNode)draft.path("dailyItinerary").get(0).path("morning"))
        .put("visitMinutes",90.5);
    tools.validate(draft.toString());
    assertNull(tools.accepted);String issue=run.report.issues().get(0);
    assertTrue(issue.contains("morning.visitMinutes"),issue);
    assertFalse(issue.contains("org."));assertFalse(issue.contains("测试安排"));
  }
  @Test void restFlagAndMinutesAreReportedTogetherBeforeAnyMapRequests()throws Exception {
    try(var maps=new TestMaps()){
      var run=AgentFixtures.mappedRun("walking");var tools=new AgentTools(run,maps);
      tools.estimate(java.math.BigDecimal.ZERO,java.math.BigDecimal.TEN,java.math.BigDecimal.TEN,
          java.math.BigDecimal.TEN,java.math.BigDecimal.TEN);
      var plan=AgentFixtures.mappedPlan();var rest=plan.getDailyItinerary().get(0).getEvening();
      rest.setRest(null);rest.setVisitMinutes(0);
      tools.validate(AgentRules.JSON.writeValueAsString(plan));
      assertNull(tools.accepted);assertEquals(0,maps.searches);
      assertTrue(run.report.issues().stream().anyMatch(s->s.contains("evening.rest")));
      assertTrue(run.report.issues().stream().anyMatch(s->s.contains("evening.visitMinutes")));
      rest.setRest(true);rest.setVisitMinutes(60);
      tools.validate(AgentRules.JSON.writeValueAsString(plan));
      assertNotNull(tools.accepted,String.valueOf(run.report));assertEquals(1,maps.searches);
    }
  }
  @Test void identicalSearchesReuseOnlyThisTasksSuccessfulResult(){
    try(var maps=new TestMaps()){
      var one=new AgentTools(AgentFixtures.mappedRun("walking"),maps);
      one.search("公园");one.search(" 公园 ");assertEquals(1,maps.searches);
      var two=new AgentTools(AgentFixtures.mappedRun("walking"),maps);
      two.search("公园");assertEquals(2,maps.searches);
    }
  }
  static class TestMaps extends AgentRouteValidationTests.Maps implements AutoCloseable {
    @Override public void close(){super.close();}
  }
}

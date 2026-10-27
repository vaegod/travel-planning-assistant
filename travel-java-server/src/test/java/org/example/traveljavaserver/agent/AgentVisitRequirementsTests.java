package org.example.traveljavaserver.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentVisitRequirementsTests {
  AgentRun run(AgentTypes.Revise revision, List<AgentTypes.VisitRequirement> requirements){
    return new AgentRun(1,new AgentTypes.Start(UUID.randomUUID().toString(),"杭州",1,new BigDecimal("1000"),"", "walking",requirements),
        revision,revision==null?null:"trip",revision==null?null:AgentFixtures.mappedPlan(),"visits",10000);
  }
  @Test void tooShortVisitsAreRejectedWithAFieldAndSuppliedMinimumWithoutInflatingTheDraft()throws Exception{
    var run=run(null,List.of(new AgentTypes.VisitRequirement("测试博物馆","B",180)));
    var tools=AgentFixtures.tools(run);var plan=AgentFixtures.mappedPlan();
    tools.validate(AgentRules.JSON.writeValueAsString(plan));assertNull(tools.accepted);
    assertTrue(run.report.issues().stream().anyMatch(s->s.contains("afternoon.visitMinutes")&&s.contains("180")));
    assertEquals(90,plan.getDailyItinerary().get(0).getAfternoon().getVisitMinutes());
  }
  @Test void modelCannotRewriteRequestBoundsAndCorrectedVisitMustStillFitTheWholeDay()throws Exception{
    var run=run(null,List.of(new AgentTypes.VisitRequirement("测试博物馆","B",180)));
    var plan=AgentFixtures.mappedPlan();plan.getDailyItinerary().get(0).getMorning().setVisitMinutes(360);
    plan.getDailyItinerary().get(0).getEvening().setVisitMinutes(90);
    var report=AgentRules.validate(run,plan);
    assertTrue(report.issues().stream().anyMatch(s->s.contains("180")));
    assertTrue(report.issues().stream().anyMatch(s->s.contains("600")),"Use required visit duration in the daily lower bound");
    var tree=(com.fasterxml.jackson.databind.node.ObjectNode)AgentRules.JSON.valueToTree(plan);
    tree.set("visitRequirements",AgentRules.JSON.valueToTree(List.of(new AgentTypes.VisitRequirement("测试博物馆","B",15))));
    assertThrows(Exception.class,()->AgentRules.candidate(run,tree.toString()));
    assertEquals(180,run.start.visitRequirements().get(0).minimumMinutes());
  }
  @Test void correctedDurationPassesAndUnknownPlaceBoundsDoNotBecomeUniversalDefaults()throws Exception{
    var run=run(null,List.of(new AgentTypes.VisitRequirement("测试博物馆","B",180)));
    var tools=AgentFixtures.tools(run);var plan=AgentFixtures.mappedPlan();
    plan.getDailyItinerary().get(0).getAfternoon().setVisitMinutes(180);
    tools.validate(AgentRules.JSON.writeValueAsString(plan));assertNotNull(tools.accepted,String.valueOf(run.report));
    assertEquals(15,AgentVisits.minimum(run,plan.getDailyItinerary().get(0).getMorning()));
    assertTrue(run.report.warnings().stream().anyMatch(s->s.contains("请求提供")));
  }
  @Test void localRevisionKeepsUnselectedLegacyActivityEvenWhenItsReferenceMinimumIsLarger()throws Exception{
    var revision=new AgentTypes.Revise(UUID.randomUUID().toString(),1,1,"afternoon","调整");
    var run=run(revision,List.of(new AgentTypes.VisitRequirement("测试公园","A",180)));
    var before=AgentRules.JSON.valueToTree(run.base);var tools=AgentFixtures.tools(run);
    tools.validate(AgentRules.JSON.writeValueAsString(Map.of("afternoon",AgentFixtures.slot("新景点"))));
    assertNotNull(tools.accepted,String.valueOf(run.report));
    assertEquals(before.path("dailyItinerary").path(0).path("morning"),AgentRules.JSON.valueToTree(tools.accepted).path("dailyItinerary").path(0).path("morning"));
  }
}

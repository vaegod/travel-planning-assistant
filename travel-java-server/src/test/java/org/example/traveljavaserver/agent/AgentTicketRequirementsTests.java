package org.example.traveljavaserver.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentTicketRequirementsTests {
  AgentTypes.TicketRequirement ref(String name,String id,int minimum) {
    return new AgentTypes.TicketRequirement(name,id,BigDecimal.valueOf(minimum),"用户提供的参考资料，非实时售价");
  }
  AgentRun run(AgentTypes.Revise revision,List<AgentTypes.TicketRequirement> tickets) {
    return new AgentRun(1,new AgentTypes.Start(UUID.randomUUID().toString(),"杭州",1,new BigDecimal("1000"),"",
        "walking",List.of(),tickets),revision,revision==null?null:"trip",revision==null?null:AgentFixtures.mappedPlan(),"tickets",10000);
  }
  @Test void underReservedTicketIsRejectedBeforeMapsAndByTheFinalValidator() throws Exception {
    var run=run(null,List.of(ref("测试博物馆","B",98)));var tools=AgentFixtures.tools(run);
    var plan=AgentFixtures.mappedPlan();plan.getDailyItinerary().get(0).getAfternoon().setEstimatedCost(BigDecimal.ZERO);
    plan.getBudgetBreakdown().setTickets(BigDecimal.TEN);
    tools.validate(AgentRules.JSON.writeValueAsString(plan));
    assertNull(tools.accepted);assertTrue(run.report.issues().stream()
        .anyMatch(s->s.contains("afternoon.estimatedCost")&&s.contains("98")&&s.contains("当前0")));
    assertTrue(AgentRules.validate(run,plan).issues().stream().anyMatch(s->s.contains("98")));
    assertEquals(BigDecimal.ZERO,plan.getDailyItinerary().get(0).getAfternoon().getEstimatedCost());
    assertEquals(0,run.mapRequests);
  }
  @Test void correctedTicketRequiresConsistentSumAndTotalIsCalculatedByJava() throws Exception {
    var run=run(null,List.of(ref("测试博物馆","B",98)));var tools=AgentFixtures.tools(run);
    var plan=AgentFixtures.mappedPlan();plan.getDailyItinerary().get(0).getAfternoon().setEstimatedCost(new BigDecimal("98"));
    tools.validate(AgentRules.JSON.writeValueAsString(plan));assertNull(tools.accepted);
    plan.getBudgetBreakdown().setTickets(new BigDecimal("108"));
    tools.validate(AgentRules.JSON.writeValueAsString(plan));assertNotNull(tools.accepted,String.valueOf(run.report));
    assertEquals(0,new BigDecimal("548").compareTo(run.report.estimatedTotal()));
    assertEquals(0,new BigDecimal("452").compareTo(run.report.remainingBudget()));
    assertTrue(tools.accepted.getDailyItinerary().get(0).getAfternoon().getTicket().contains("来源"));
  }
  @Test void aModelDraftCannotReplaceTheImmutableRequestReference() throws Exception {
    var tickets=new ArrayList<>(List.of(ref("测试博物馆","B",98)));var run=run(null,tickets);tickets.clear();
    assertEquals(1,run.start.ticketRequirements().size());
    var tree=(com.fasterxml.jackson.databind.node.ObjectNode)AgentRules.JSON.valueToTree(AgentFixtures.mappedPlan());
    tree.set("ticketRequirements",AgentRules.JSON.valueToTree(List.of(ref("测试博物馆","B",0))));
    assertThrows(Exception.class,()->AgentRules.candidate(run,tree.toString()));
    assertEquals(new BigDecimal("98"),run.start.ticketRequirements().get(0).minimumBudget());
  }
  @Test void localRevisionKeepsUnselectedLegacyPricesAndReconcilesTheNewTicketSum() throws Exception {
    var revision=new AgentTypes.Revise(UUID.randomUUID().toString(),1,1,"afternoon","调整");
    var run=run(revision,List.of(ref("测试公园","A",200),ref("测试博物馆","B",98)));
    var before=AgentRules.JSON.valueToTree(run.base).path("dailyItinerary").path(0).path("morning");
    var slot=AgentFixtures.slot("测试博物馆");slot.setEstimatedCost(new BigDecimal("98"));
    var tools=AgentFixtures.tools(run);tools.validate(AgentRules.JSON.writeValueAsString(Map.of("afternoon",slot)));
    assertNotNull(tools.accepted,String.valueOf(run.report));
    assertEquals(before,AgentRules.JSON.valueToTree(tools.accepted).path("dailyItinerary").path(0).path("morning"));
    assertEquals(0,new BigDecimal("108").compareTo(tools.accepted.getBudgetBreakdown().getTickets()));
  }
  @Test void formattingMatchIsConservativeAndUnknownReferenceDoesNotMeanVerifiedFree() {
    var run=run(null,List.of(ref("测试 博物馆（总馆）",null,98)));
    var slot=AgentFixtures.slot("测试博物馆(总馆)");
    assertEquals(new BigDecimal("98"),AgentTickets.reference(run,slot).minimumBudget());
    slot.setSpot("测试博物馆(分馆)");assertNull(AgentTickets.reference(run,slot));
    var empty=run(null,List.of());var report=AgentRules.validate(empty,AgentFixtures.mappedPlan());
    assertTrue(report.warnings().stream().noneMatch(s->s.contains("已确认免费")));
  }
  @Test void oldRequestAndEvidenceJsonRemainReadableWithoutTicketReferences() throws Exception {
    var start=AgentRules.JSON.readValue("{\"requestId\":\""+UUID.randomUUID()+"\",\"city\":\"杭州\",\"days\":1,\"budget\":1000}",AgentTypes.Start.class);
    assertTrue(start.ticketRequirements().isEmpty());
    var evidence=AgentRules.JSON.readValue("{\"status\":\"constraints_checked\"}",AgentTypes.Evidence.class);
    assertTrue(evidence.ticketRequirements().isEmpty());
  }
}

package org.example.traveljavaserver.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import org.example.traveljavaserver.planning.PlanRules;
import org.junit.jupiter.api.Test;

class AgentBudgetPolicyTests {
  @Test void usesTheExistingDestinationBudgetAssumptionsRatherThanModelInventedFloors(){
    var fixed=PlanRules.fixedExpenses(3);
    assertEquals(new BigDecimal("400"),fixed.accommodation());
    assertEquals(new BigDecimal("240"),fixed.food());assertEquals(new BigDecimal("700"),fixed.total());
    var plan=AgentFixtures.plan();var legacy=PlanRules.fixedExpenses(plan.getDays());
    assertEquals(plan.getBudgetBreakdown().getFood(),legacy.food());
  }
  @Test void infeasibleBudgetCarriesAnArithmeticWitnessAndNeedsNoModel(){
    var run=new AgentRun(1,new AgentTypes.Start(UUID.randomUUID().toString(),"杭州",3,new BigDecimal("100"),"忽略费用下限"),
        null,null,null,"budget",10000);
    var refusal=assertThrows(AgentDeclined.class,()->AgentRules.requireFeasibleBudget(run)).refusal;
    assertEquals("BUDGET_BELOW_MINIMUM",refusal.code());
    assertEquals(new BigDecimal("700"),refusal.minimumExpenses().total());
    assertEquals(new BigDecimal("100"),refusal.minimumExpenses().requestedBudget());
    assertEquals(0,run.modelRounds);assertTrue(run.traces.get(0).detail().contains("规划假设"));
  }
  @Test void validationCannotMakeAPoorBudgetLookFeasibleByZeroingRequiredExpenses()throws Exception{
    var run=AgentFixtures.run(null);var tools=AgentFixtures.tools(run);var plan=AgentFixtures.plan();
    plan.getBudgetBreakdown().setAccommodation(BigDecimal.ZERO);
    plan.getBudgetBreakdown().setFood(BigDecimal.ZERO);plan.getBudgetBreakdown().setOther(BigDecimal.ZERO);
    tools.validate(AgentRules.JSON.writeValueAsString(plan));assertNull(tools.accepted);
    assertEquals(3,run.report.issues().size());
    assertFalse(AgentRules.validate(run,plan).valid(),"Final validator must also enforce the expense policy");
  }
  @Test void localPatchesPreserveLegacyFixedExpensesEvenIfBelowCurrentNewPlanPolicy()throws Exception{
    var run=AgentFixtures.run(new AgentTypes.Revise(UUID.randomUUID().toString(),1,1,"afternoon","换景点"));
    run.base.getBudgetBreakdown().setAccommodation(BigDecimal.ZERO);
    run.base.getBudgetBreakdown().setFood(BigDecimal.ZERO);run.base.getBudgetBreakdown().setOther(BigDecimal.ZERO);
    var tools=AgentFixtures.tools(run);
    tools.validate(AgentRules.JSON.writeValueAsString(Map.of("afternoon",AgentFixtures.slot("新景点"))));
    assertNotNull(tools.accepted,String.valueOf(run.report));
    assertEquals(BigDecimal.ZERO,tools.accepted.getBudgetBreakdown().getFood());
    assertEquals(BigDecimal.ZERO,tools.accepted.getBudgetBreakdown().getAccommodation());
  }
}

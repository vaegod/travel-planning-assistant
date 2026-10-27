package org.example.traveljavaserver.agent;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AgentFlowTests {
  @Test void explorationBudgetClosesQueriesAndKeepsSubmissionAndRefusalAvailable(){
    var run=AgentFixtures.run(null);run.budgetEstimated=true;run.modelQueries=3;
    assertEquals(java.util.Set.of("validate_itinerary","report_infeasible"),AgentFlow.offered(run,8));
    assertFalse(AgentFlow.canExecute(run,"route_places"));assertFalse(AgentFlow.canExecute(run,"estimate_budget"));
    assertTrue(AgentFlow.canExecute(run,"validate_itinerary"));
  }
  @Test void lastTwoRoundsAreReservedForDraftRepairWithoutIncreasingTheRoundCap(){
    var run=AgentFixtures.run(null);run.modelRounds=6;run.budgetEstimated=true;
    assertEquals(java.util.Set.of("validate_itinerary","report_infeasible"),AgentFlow.offered(run,8));
    assertTrue(run.forceDraft);assertEquals(6,run.modelRounds);
  }
  @Test void budgetMustBeAnActualToolResultBeforeSubmission(){
    var run=AgentFixtures.run(null);run.forceDraft=true;
    assertEquals(java.util.Set.of("estimate_budget","report_infeasible"),AgentFlow.offered(run,8));
    assertFalse(AgentFlow.canExecute(run,"validate_itinerary"));
    AgentFixtures.tools(run);assertTrue(run.budgetEstimated);assertTrue(AgentFlow.canExecute(run,"validate_itinerary"));
  }
}

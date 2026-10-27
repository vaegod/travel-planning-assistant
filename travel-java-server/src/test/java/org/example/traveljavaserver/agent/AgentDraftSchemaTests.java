package org.example.traveljavaserver.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentDraftSchemaTests {
  @Test void newPlanAdvertisesEveryDaySlotAndNumericBoundRatherThanAnEmptyObject()throws Exception{
    var schema=AgentRules.JSON.readTree(AgentDraftSchema.input(AgentFixtures.run(null)));
    var draft=schema.path("properties").path("draftJson");
    assertTrue(draft.path("required").toString().contains("dailyItinerary"));
    var days=draft.path("properties").path("dailyItinerary");
    assertEquals(2,days.path("minItems").asInt());assertEquals(2,days.path("maxItems").asInt());
    for(String slot:AgentRules.SLOTS){
      var value=days.path("items").path("properties").path(slot);
      assertTrue(value.path("required").toString().contains("visitMinutes"));
      assertEquals(15,value.path("properties").path("visitMinutes").path("minimum").asInt());
      assertFalse(value.path("properties").has("route"));assertFalse(value.path("additionalProperties").asBoolean());
    }
  }
  @Test void revisionSchemaOnlyAdvertisesSelectedSlotsAndOptionalBudget()throws Exception{
    var run=AgentFixtures.run(new AgentTypes.Revise(UUID.randomUUID().toString(),1,1,"afternoon","调整"));
    var draft=AgentRules.JSON.readTree(AgentDraftSchema.input(run)).path("properties").path("draftJson");
    var fields=new HashSet<String>();draft.path("properties").fieldNames().forEachRemaining(fields::add);
    assertEquals(Set.of("afternoon","budgetBreakdown"),fields);
    assertEquals(List.of("afternoon"),AgentRules.JSON.convertValue(draft.path("required"),List.class));
    assertFalse(draft.path("additionalProperties").asBoolean());
  }
}

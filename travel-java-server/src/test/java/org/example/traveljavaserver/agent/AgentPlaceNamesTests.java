package org.example.traveljavaserver.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentPlaceNamesTests {
  static final String NAME="悬空飞越剧场(春熙路茂业百货店)";
  AgentTools tools(AgentRun run,AgentRouteValidationTests.Maps maps){
    var tools=new AgentTools(run,maps);tools.search("景点");
    tools.places.put("A",maps.place("A",NAME));
    tools.estimate(java.math.BigDecimal.ZERO,new java.math.BigDecimal("80"),new java.math.BigDecimal("30"),
        new java.math.BigDecimal("20"),new java.math.BigDecimal("20"));return tools;
  }
  @Test void uniqueWhitespaceAndFullWidthFormattingRestoresOnlyQueriedIdentity()throws Exception{
    var maps=new AgentRouteValidationTests.Maps();try{
      var run=AgentFixtures.mappedRun("walking");var tools=tools(run,maps);var plan=AgentFixtures.mappedPlan();
      var slot=plan.getDailyItinerary().get(0).getMorning();slot.setSpot("　悬空飞越剧场 （春熙路茂业百货店）");slot.setPlaceId(null);
      tools.validate(AgentRules.JSON.writeValueAsString(plan));assertNotNull(tools.accepted,String.valueOf(run.report));
      var result=tools.accepted.getDailyItinerary().get(0).getMorning();
      assertEquals("A",result.getPlaceId());assertEquals(NAME,result.getSpot());assertEquals("fresh-query",result.getQueriedAt());
      assertEquals(1,maps.searches);assertEquals("A",tools.accepted.getDailyItinerary().get(0).getAfternoon().getRoute().fromId());
    }finally{maps.close();}
  }
  @Test void normalizedCollisionsRequireAnActualQueriedId()throws Exception{
    var maps=new AgentRouteValidationTests.Maps();try{
      var run=AgentFixtures.mappedRun("walking");var tools=tools(run,maps);
      tools.places.put("C",maps.place("C","悬空飞越剧场 （春熙路茂业百货店）"));
      var plan=AgentFixtures.mappedPlan();var slot=plan.getDailyItinerary().get(0).getMorning();
      slot.setSpot("悬空 飞越剧场(春熙路茂业百货店)");slot.setPlaceId(null);
      tools.validate(AgentRules.JSON.writeValueAsString(plan));assertNull(tools.accepted);
      assertTrue(run.report.issues().get(0).contains("多个地点"));
      slot.setPlaceId("A");tools.validate(AgentRules.JSON.writeValueAsString(plan));assertNotNull(tools.accepted,String.valueOf(run.report));
    }finally{maps.close();}
  }
  @Test void partialNamesAndDifferentBranchesDoNotAcquireProvenance()throws Exception{
    var maps=new AgentRouteValidationTests.Maps();try{
      var run=AgentFixtures.mappedRun("walking");var tools=tools(run,maps);var plan=AgentFixtures.mappedPlan();
      var slot=plan.getDailyItinerary().get(0).getMorning();slot.setPlaceId(null);
      for(String name:List.of("悬空飞越剧场","悬空飞越剧场(其他店)")){
        slot.setSpot(name);tools.validate(AgentRules.JSON.writeValueAsString(plan));assertNull(tools.accepted);
      }
      assertNotEquals(PlaceNames.key("场馆①"),PlaceNames.key("场馆1"));
    }finally{maps.close();}
  }
}

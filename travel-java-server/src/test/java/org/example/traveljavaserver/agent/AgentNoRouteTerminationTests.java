package org.example.traveljavaserver.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import org.example.traveljavaserver.common.AppException;
import org.example.traveljavaserver.utils.LLMutils;
import org.junit.jupiter.api.*;

class AgentNoRouteTerminationTests {
  AgentRouteValidationTests.Maps maps;
  @BeforeEach void setup(){maps=new AgentRouteValidationTests.Maps();maps.unavailable=true;}
  @AfterEach void close(){maps.close();}
  AgentEngine engine(boolean alternate,boolean modelFailure)throws Exception{
    var llm=mock(LLMutils.class);var call=mock(Call.class);var round=new AtomicInteger();
    when(call.timeout()).thenReturn(new okio.Timeout());
    when(llm.newToolChatCall(anyList(),anyList(),anyInt())).thenReturn(call);
    when(llm.readToolCompletion(call)).thenAnswer(invocation->{
      int index=round.getAndIncrement();
      if(modelFailure&&index==2)throw AgentRun.error("MODEL_TIMEOUT","fixture timeout");
      String name=index==0?"search_places":"route_places";
      if(alternate&&index==2)maps.unavailable=false;
      Object args=index==0?Map.of("keywords","公园"):
          Map.of("fromPlaceId","A","toPlaceId",alternate&&index==2?"C":"B");
      return AgentRules.JSON.valueToTree(Map.of("content","","tool_calls",List.of(Map.of("id","call_"+index,
          "type","function","function",Map.of("name",name,"arguments",AgentRules.JSON.writeValueAsString(args))))));
    });
    return new AgentEngine(llm,maps,6,16);
  }
  @Test void confirmedNoRouteSurvivesAModelLoopLimitAndBecomesAVisibleBusinessOutcome()throws Exception{
    var failure=assertThrows(AgentDeclined.class,()->engine(false,false).execute(AgentFixtures.mappedRun("transit")));
    assertEquals("ROUTE_UNAVAILABLE",failure.refusal.code());assertTrue(failure.getMessage().contains("当前候选路线"));
    assertEquals(1,maps.routes,"Repeated unavailable edge uses the task cache");
  }
  @Test void successfulAlternateRouteClearsAnEarlierUnavailableRouteBeforeALimit()throws Exception{
    var failure=assertThrows(AppException.class,()->engine(true,false).execute(AgentFixtures.mappedRun("transit")));
    assertFalse(failure instanceof AgentDeclined);assertEquals("AGENT_ROUND_LIMIT",failure.getErrorCode());
    assertEquals(2,maps.routes);
  }
  @Test void modelInfrastructureFailureIsNotReclassifiedAsBusinessInfeasibility()throws Exception{
    var failure=assertThrows(AppException.class,()->engine(false,true).execute(AgentFixtures.mappedRun("transit")));
    assertFalse(failure instanceof AgentDeclined);assertEquals("AGENT_TIMEOUT",failure.getErrorCode());
  }
}

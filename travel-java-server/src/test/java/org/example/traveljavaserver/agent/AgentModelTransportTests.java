package org.example.traveljavaserver.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.mockwebserver.*;
import org.example.traveljavaserver.common.AppException;
import org.example.traveljavaserver.utils.*;
import org.junit.jupiter.api.Test;

class AgentModelTransportTests {
  static AgentRun run(long timeout) {
    return new AgentRun(1,new AgentTypes.Start(UUID.randomUUID().toString(),"杭州",2,new BigDecimal("1000"),""),
        null,null,null,"transport",timeout);
  }
  static final List<Map<String,Object>> MESSAGES=List.of(Map.of("role","user","content","fixture-private-content"));
  static MockResponse ok() { return new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"); }
  static MockResponse tool(String name,Object arguments) throws Exception {
    return new MockResponse().setBody(AgentRules.JSON.writeValueAsString(Map.of("choices",List.of(Map.of(
        "message",Map.of("content","","tool_calls",List.of(Map.of("id",UUID.randomUUID().toString(),"type","function",
        "function",Map.of("name",name,"arguments",AgentRules.JSON.writeValueAsString(arguments))))),"finish_reason","tool_calls")),
        "usage",Map.of("prompt_tokens",1,"completion_tokens",1))));
  }
  @Test void disconnectedResponseRecoversOnFreshConnectionAndExecutesEachToolOnce() throws Exception {
    try(var server=new MockWebServer();var maps=new ClosingMaps()) {
      server.enqueue(tool("estimate_budget",AgentFixtures.plan().getBudgetBreakdown()));
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
      server.enqueue(tool("validate_itinerary",Map.of("draftJson",AgentFixtures.plan())));
      try(var llm=new ClosingLLM(server)) {
        var run=run(180000);
        var result=new AgentEngine(llm,maps,8,16).execute(run);
        assertTrue(result.getSuccess());assertEquals(3,run.modelRounds);assertEquals(1,run.recoveryAttempts);
        assertEquals(1,run.traces.stream().filter(t->t.tool().equals("estimate_budget")).count());
        assertEquals(1,run.traces.stream().filter(t->t.tool().equals("validate_itinerary")).count());
        var first=server.takeRequest();var failed=server.takeRequest();var recovered=server.takeRequest();
        assertEquals(failed.getBody().readUtf8(),recovered.getBody().readUtf8());
        assertEquals(0,recovered.getSequenceNumber(),"Recovery must not reuse the failed connection");
        assertEquals(failed.getHeader("X-Travel-Model-Attempt"),recovered.getHeader("X-Travel-Model-Recovery-Of"));
        assertEquals(3,run.modelCalls.size());var error=run.modelCalls.get(1);
        assertEquals("MODEL_CONNECTION_ERROR",error.status());assertTrue(error.requestSent());
        assertEquals("awaiting_response",error.phase());assertFalse(error.responseStarted());
        assertEquals(error.attemptId(),run.modelCalls.get(2).recoveryOf());
        String trace=AgentRules.JSON.writeValueAsString(run.modelCalls);
        assertFalse(trace.contains("fixture-secret-key"));assertFalse(trace.contains("fixture-private-content"));
      }
    }
  }
  @Test void timeoutHasBoundedRecoveryAndRetainsBothPhysicalAttempts() throws Exception {
    try(var server=new MockWebServer();var llm=new ClosingLLM(server)) {
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));server.enqueue(ok());
      var run=run(180000);
      try(var scope=ModelTrace.open(run.id,"transport-test",run.modelCalls::add)) {
        assertEquals("ok",AgentModelTransport.complete(llm,run,8,250,MESSAGES,List.of(),4096).path("content").asText());
      }
      assertEquals(2,run.modelRounds);assertEquals(1,run.recoveryAttempts);assertEquals(2,run.modelCalls.size());
      assertEquals("MODEL_TIMEOUT",run.modelCalls.get(0).status());
      assertNotNull(run.modelCalls.get(1).recoveryOf());assertTrue(run.modelCalls.get(1).durationMs()>=0);
    }
  }
  @Test void connectionLossWhileReadingTheBodyIsDiagnosedAndRecoveredBeforeAnyToolDispatch() throws Exception {
    try(var server=new MockWebServer();var llm=new ClosingLLM(server)) {
      server.enqueue(ok().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));server.enqueue(ok());
      var run=run(180000);
      try(var scope=ModelTrace.open(run.id,"body-loss-test",run.modelCalls::add)) {
        assertEquals("ok",AgentModelTransport.complete(llm,run,8,1000,MESSAGES,List.of(),4096).path("content").asText());
      }
      assertEquals(1,run.recoveryAttempts);var failed=run.modelCalls.get(0);
      assertEquals("MODEL_CONNECTION_ERROR",failed.status());assertTrue(failed.responseStarted());
      assertEquals("response_body",failed.phase());assertNotNull(failed.exceptionType());
    }
  }
  @Test void recoveryIsLimitedToOnePerJobAndCountsAgainstTheOriginalRoundLimit() throws Exception {
    try(var server=new MockWebServer();var llm=new ClosingLLM(server)) {
      server.enqueue(new MockResponse().setResponseCode(503));server.enqueue(ok());server.enqueue(new MockResponse().setResponseCode(503));
      var run=run(180000);
      AgentModelTransport.complete(llm,run,8,500,MESSAGES,List.of(),4096);
      assertThrows(AppException.class,()->AgentModelTransport.complete(llm,run,8,500,MESSAGES,List.of(),4096));
      assertEquals(3,run.modelRounds);assertEquals(1,run.recoveryAttempts);assertEquals(3,server.getRequestCount());
    }
    try(var server=new MockWebServer();var llm=new ClosingLLM(server)) {
      server.enqueue(new MockResponse().setResponseCode(503));
      var run=run(180000);
      assertThrows(AppException.class,()->AgentModelTransport.complete(llm,run,1,500,MESSAGES,List.of(),4096));
      assertEquals(1,run.modelRounds);assertEquals(0,run.recoveryAttempts);
    }
  }
  @Test void authenticationRateLimitInvalidJsonAndOtherHttpErrorsAreNotRetried() throws Exception {
    for(int status:new int[]{400,401,402,403,404,429,500}) {
      try(var server=new MockWebServer();var llm=new ClosingLLM(server)) {
        server.enqueue(new MockResponse().setResponseCode(status));
        var run=run(180000);
        assertThrows(AppException.class,()->AgentModelTransport.complete(llm,run,8,500,MESSAGES,List.of(),4096));
        assertEquals(0,run.recoveryAttempts,"status "+status);assertEquals(1,server.getRequestCount());
      }
    }
    try(var server=new MockWebServer();var llm=new ClosingLLM(server)) {
      server.enqueue(new MockResponse().setBody("{broken"));var run=run(180000);
      assertEquals("INVALID_MODEL_RESPONSE",assertThrows(AppException.class,
          ()->AgentModelTransport.complete(llm,run,8,500,MESSAGES,List.of(),4096)).getErrorCode());
      assertEquals(0,run.recoveryAttempts);
    }
  }
  @Test void cancellationStopsTheActiveCallBeforeAnyRecovery() throws Exception {
    try(var server=new MockWebServer();var llm=new ClosingLLM(server)) {
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));var run=run(180000);
      var executor=Executors.newSingleThreadExecutor();
      try {
        var future=executor.submit(()->AgentModelTransport.complete(llm,run,8,5000,MESSAGES,List.of(),4096));
        assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));run.cancel();
        assertInstanceOf(CancellationException.class,assertThrows(ExecutionException.class,()->future.get(2,TimeUnit.SECONDS)).getCause());
        assertEquals(0,run.recoveryAttempts);
      } finally { executor.shutdownNow(); }
    }
  }
  @Test void insufficientRemainingBudgetDoesNotStartAnExtraRequest() throws Exception {
    try(var server=new MockWebServer();var llm=new ClosingLLM(server)) {
      server.enqueue(new MockResponse().setResponseCode(503));var run=run(10000);
      assertThrows(AppException.class,()->AgentModelTransport.complete(llm,run,8,500,MESSAGES,List.of(),4096));
      assertEquals(0,run.recoveryAttempts);
    }
  }
  @Test void concurrentHttp2RecoveriesCannotShareAnActiveConnection() throws Exception {
    try(var server=new MockWebServer()) {
      server.setProtocols(List.of(okhttp3.Protocol.H2_PRIOR_KNOWLEDGE));
      var firstRecovery=new CountDownLatch(1);var secondRecovery=new CountDownLatch(1);
      var initials=new java.util.concurrent.atomic.AtomicInteger();var recoveries=new java.util.concurrent.atomic.AtomicInteger();
      server.setDispatcher(new Dispatcher() {
        @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
          if(request.getHeader("X-Travel-Model-Recovery-Of")==null) {
            if(initials.incrementAndGet()==2&&!firstRecovery.await(5,TimeUnit.SECONDS))return new MockResponse().setResponseCode(500);
            return new MockResponse().setResponseCode(503);
          }
          if(recoveries.incrementAndGet()==1) {
            firstRecovery.countDown();
            if(!secondRecovery.await(5,TimeUnit.SECONDS))return new MockResponse().setResponseCode(500);
          } else secondRecovery.countDown();
          return ok();
        }
      });
      var client=new okhttp3.OkHttpClient.Builder().protocols(List.of(okhttp3.Protocol.H2_PRIOR_KNOWLEDGE)).build();
      try(var llm=new ClosingLLM(server,client)) {
        var one=run(180000);var two=run(180000);var executor=Executors.newFixedThreadPool(2);
        try {
          var tasks=List.of(one,two).stream().map(run->executor.submit(()->{
            try(var scope=ModelTrace.open(run.id,"concurrent-test",run.modelCalls::add)) {
              return AgentModelTransport.complete(llm,run,8,7000,MESSAGES,List.of(),4096);
            }
          })).toList();
          for(var task:tasks)assertEquals("ok",task.get(9,TimeUnit.SECONDS).path("content").asText());
          for(var run:List.of(one,two)) {
            assertEquals(1,run.recoveryAttempts);assertEquals(2,run.modelCalls.size());
            assertFalse(run.modelCalls.get(1).connectionReused(),"Concurrent recovery needs a separate physical connection");
          }
          assertEquals(2,recoveries.get());assertEquals(4,server.getRequestCount());
        } finally { executor.shutdownNow(); }
      }
    }
  }
  static class ClosingLLM extends LLMutils implements AutoCloseable {
    ClosingLLM(MockWebServer server){super("fixture-secret-key",server.url("/").toString(),"fixture-model",10);}
    ClosingLLM(MockWebServer server,okhttp3.OkHttpClient client){super("fixture-secret-key",server.url("/").toString(),"fixture-model",client);}
    @Override public void close(){super.close();}
  }
  static class ClosingMaps extends AgentMaps implements AutoCloseable {
    ClosingMaps(){super("","https://restapi.amap.com");}
    @Override public void close(){super.close();}
  }
}

package org.example.traveljavaserver.agent;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.example.traveljavaserver.TestApi;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"agent.max-rounds=5","agent.timeout-ms=5000"})
class AgentHttpTests {
  static volatile String mode="ok";
  static volatile CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
  static final List<JsonNode> requests=new CopyOnWriteArrayList<>();
  static final java.util.concurrent.atomic.AtomicInteger budgetOnlyReplies=new java.util.concurrent.atomic.AtomicInteger();
  static final ExecutorService threads=Executors.newCachedThreadPool(r->{var t=new Thread(r);t.setDaemon(true);return t;});
  static final HttpServer provider=provider();
  @LocalServerPort int port;
  TestApi api;
  @DynamicPropertySource static void config(DynamicPropertyRegistry p) {
    p.add("llm.base-url",()->"http://127.0.0.1:"+provider.getAddress().getPort());
    p.add("llm.api-key",()->"agent-fixture-only");
    p.add("agent.amap-key",()->"");
  }
  static HttpServer provider() {
    try {
      var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(threads);
      server.createContext("/chat/completions",e->{
        try {
          var request=TestApi.JSON.readTree(e.getRequestBody());requests.add(request);
          if(mode.equals("refuse_json")){
            send(e,200,Map.of("choices",List.of(Map.of("message",Map.of("role","assistant","content","{\"success\":false,\"error\":\"预算不足，无法满足住宿费用\"}"),"finish_reason","stop"))));return;
          }
          if(mode.equals("refuse")){
            send(e,200,Map.of("choices",List.of(Map.of("message",Map.of("role","assistant","content","","tool_calls",List.of(Map.of("id","refusal","type","function","function",Map.of("name","report_infeasible","arguments","{\"reason\":\"公交无可用方案，无法满足必须公交的要求\"}")))),"finish_reason","tool_calls"))));return;
          }
          if(mode.equals("slow")){started.countDown();release.await(8,TimeUnit.SECONDS);}
          if(mode.equals("reject")){send(e,400,Map.of("error","fixture rejection"));return;}
          var messages=request.path("messages");
          var results=new ArrayList<JsonNode>();String user="";
          for(var m:messages){if(m.path("role").asText().equals("tool"))results.add(TestApi.JSON.readTree(m.path("content").asText()));
            if(m.path("role").asText().equals("user"))user+=m.path("content").asText();}
          if(mode.equals("budget_text")&&results.size()==1&&budgetOnlyReplies.getAndIncrement()==0){
            send(e,200,Map.of("choices",List.of(Map.of("message",Map.of("role","assistant","content","预算核算完成，需要我继续安排每日行程吗？"),"finish_reason","stop"))));return;
          }
          if(mode.equals("none") || (results.size()>=2 && !Set.of("loop","after_valid_loop","explore","budget_text").contains(mode) &&
              (!Set.of("repair","malformed","visit_minimum","ticket_minimum").contains(mode) || results.get(results.size()-1).path("valid").asBoolean()))) {
            send(e,200,Map.of("choices",List.of(Map.of("message",Map.of("role","assistant","content","草稿已完成"),"finish_reason","stop"))));return;
          }
          String name; Object args;
          if(mode.equals("unknown")){name="delete_trip";args=Map.of();}
          else if(results.isEmpty()||mode.equals("loop")){name="estimate_budget";args=Map.of("accommodation",200,"food",100,"transportation",40,"tickets",60,"other",20);}
          else if(mode.equals("explore")&&java.util.stream.StreamSupport.stream(request.path("tools").spliterator(),false)
              .anyMatch(t->t.path("function").path("name").asText().equals("search_places"))){name="search_places";args=Map.of("keywords","公园");}
          else {
            name="validate_itinerary"; Object draft=AgentFixtures.plan();
            if(user.contains("这是局部修改"))draft=Map.of("afternoon",AgentFixtures.slot("新博物馆"));
            if(mode.equals("repair")&&results.size()==1){var plan=AgentFixtures.plan();plan.getBudgetBreakdown().setFood(new java.math.BigDecimal("900"));draft=plan;}
            if(mode.equals("visit_minimum")&&!user.contains("这是局部修改")&&results.size()>1){var plan=AgentFixtures.plan();plan.getDailyItinerary().get(0).getMorning().setVisitMinutes(180);draft=plan;}
            if(mode.equals("ticket_minimum")&&!user.contains("这是局部修改")&&results.size()>1){
              var plan=AgentFixtures.plan();plan.getDailyItinerary().get(0).getMorning().setEstimatedCost(new java.math.BigDecimal("98"));
              plan.getBudgetBreakdown().setTickets(new java.math.BigDecimal("148"));draft=plan;
            }
            args=Map.of("draftJson",mode.equals("object") ? draft : TestApi.JSON.writeValueAsString(draft));
            if(mode.equals("malformed")&&results.size()==1)args=Map.of("draftJson",TestApi.JSON.writeValueAsString(draft)+"}");
          }
          send(e,200,Map.of("choices",List.of(Map.of("message",Map.of("role","assistant","content","",
              "tool_calls",List.of(Map.of("id","tool_"+results.size(),"type","function","function",
                  Map.of("name",name,"arguments",TestApi.JSON.writeValueAsString(args))))),"finish_reason","tool_calls")),
              "usage",Map.of("prompt_tokens",100,"completion_tokens",50)));
        }catch(Exception ex){try{send(e,500,Map.of("error","fixture"));}catch(Exception ignored){}}
      });server.start();return server;
    }catch(Exception e){throw new IllegalStateException(e);}
  }
  static void send(HttpExchange e,int code,Object value)throws Exception{
    byte[] bytes=TestApi.JSON.writeValueAsBytes(value);e.getResponseHeaders().set("Content-Type","application/json");
    e.sendResponseHeaders(code,bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}
  }
  @BeforeEach void setup()throws Exception{mode="ok";requests.clear();budgetOnlyReplies.set(0);started=new CountDownLatch(1);release=new CountDownLatch(1);api=new TestApi(port);api.registerLogin();}
  @AfterEach void cleanup(){release.countDown();}
  @AfterAll static void close(){provider.stop(0);threads.shutdownNow();}
  String body(String id)throws Exception{return TestApi.JSON.writeValueAsString(Map.of("requestId",id,"city","杭州","days",2,"budget",1000,"instruction","轻松一点"));}
  JsonNode create()throws Exception{return api.data("POST","/api/agent/jobs",body(UUID.randomUUID().toString()));}
  JsonNode await(String id)throws Exception{
    for(int i=0;i<140;i++){var j=api.data("GET","/api/agent/jobs/"+id,null);
      if(!Set.of("QUEUED","RUNNING").contains(j.path("status").asText()))return j;Thread.sleep(50);}
    throw new AssertionError("agent job did not terminate");
  }
  JsonNode ready()throws Exception{
    var job=await(create().path("id").asText());assertEquals("READY",job.path("status").asText(),job.toPrettyString());return job;
  }
  @Test void springAiToolRoundTripCreatesValidatedDraftAndSavesIdempotently()throws Exception{
    var job=ready();assertEquals(2,job.path("toolCalls").asInt());assertTrue(job.path("validation").path("valid").asBoolean());
    assertTrue(requests.get(0).path("tools").size()>=4);
    assertTrue(requests.get(1).path("messages").toString().contains("tool_call_id"));
    var saved=api.data("POST","/api/agent/jobs/"+job.path("id").asText()+"/save","{}");
    var again=api.data("POST","/api/agent/jobs/"+job.path("id").asText()+"/save","{}");
    assertEquals(saved.path("trip").path("id"),again.path("trip").path("id"));
    assertEquals(1,again.path("trip").path("currentVersion").asInt());
    assertEquals("constraints_checked",saved.path("plan").path("agentEvidence").path("status").asText());
    assertEquals(400,api.send("POST","/api/trips",TestApi.JSON.writeValueAsString(Map.of("plan",saved.path("plan")))).statusCode());
  }
  @Test void returnsValidationFeedbackToModelAndRepairsOverspend()throws Exception{
    mode="repair";var job=ready();assertEquals(3,job.path("toolCalls").asInt());
    assertTrue(job.path("traces").toString().contains("invalid"));
    assertTrue(requests.get(2).path("messages").toString().contains("超过预算"));
  }
  @Test void acceptsCompatibleProvidersReturningDraftAsObject()throws Exception{
    mode="object";var job=ready();assertTrue(job.path("validation").path("valid").asBoolean());
  }
  @Test void draftToolAdvertisesAnObjectAndMalformedLegacyStringsGetActionableFeedback()throws Exception{
    mode="malformed";var job=ready();assertEquals(3,job.path("modelRounds").asInt());
    var tool=requests.stream().flatMap(r->java.util.stream.StreamSupport.stream(r.path("tools").spliterator(),false))
        .filter(t->t.path("function").path("name").asText().equals("validate_itinerary")).findFirst().orElseThrow();
    assertEquals("object",tool.path("function").path("parameters").path("properties").path("draftJson").path("type").asText());
    assertTrue(tool.path("function").path("parameters").path("properties").path("draftJson").path("properties").has("dailyItinerary"));
    assertTrue(requests.get(2).path("messages").toString().contains("JSON语法错误"));
    assertTrue(requests.get(2).path("messages").toString().contains("列"));
  }
  @Test void localRevisionOnlyChangesSelectedSlotAndPreservesOldVersion()throws Exception{
    var original=api.data("POST","/api/trips",TestApi.JSON.writeValueAsString(Map.of("title","我的原行程","plan",AgentFixtures.plan())));
    String trip=original.path("trip").path("id").asText();
    var revision=api.data("POST","/api/agent/trips/"+trip+"/revisions",TestApi.JSON.writeValueAsString(
        Map.of("requestId",UUID.randomUUID().toString(),"baseVersion",1,"day",2,"slot","afternoon","instruction","换成博物馆")));
    var job=await(revision.path("id").asText());assertEquals("READY",job.path("status").asText(),job.toPrettyString());
    assertEquals(1,job.path("changes").size());
    var saved=api.data("POST","/api/agent/jobs/"+job.path("id").asText()+"/save","{}");
    assertEquals(2,saved.path("trip").path("currentVersion").asInt());assertEquals("我的原行程",saved.path("trip").path("title").asText());
    assertEquals(original.path("plan").path("dailyItinerary").path(0),saved.path("plan").path("dailyItinerary").path(0));
    assertEquals(original.path("plan"),api.data("GET","/api/trips/"+trip+"?version=1",null).path("plan"));
  }
  @Test void staleBaseVersionCannotOverwriteNewerTrip()throws Exception{
    var original=api.data("POST","/api/trips",TestApi.JSON.writeValueAsString(Map.of("plan",AgentFixtures.plan())));
    String trip=original.path("trip").path("id").asText();
    String revisionBody=TestApi.JSON.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),"baseVersion",1,"day",2,"slot","afternoon","instruction","调整"));
    var job=await(api.data("POST","/api/agent/trips/"+trip+"/revisions",revisionBody).path("id").asText());
    assertEquals("READY",job.path("status").asText(),job.toPrettyString());
    api.data("PUT","/api/trips/"+trip,TestApi.JSON.writeValueAsString(Map.of("plan",AgentFixtures.plan(),"baseVersion",1)));
    assertEquals(409,api.send("POST","/api/agent/jobs/"+job.path("id").asText()+"/save","{}").statusCode());
  }
  @Test void ownerIsolationAndCsrfAreEnforced()throws Exception{
    var job=ready();var other=new TestApi(port);other.registerLogin();String path="/api/agent/jobs/"+job.path("id").asText();
    assertEquals(404,other.send("GET",path,null).statusCode());
    assertEquals(404,other.send("DELETE",path,null).statusCode());
    assertEquals(404,other.send("POST",path+"/save","{}").statusCode());
    var anonymous=new TestApi(port);assertEquals(401,anonymous.send("GET",path,null).statusCode());
    assertEquals(403,api.client.send(api.request("POST","/api/agent/jobs",body(UUID.randomUUID().toString()),false),
        java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
  }
  @Test void requestReplayReturnsSameJobButDifferentParametersConflict()throws Exception{
    String id=UUID.randomUUID().toString(),body=body(id);var first=api.data("POST","/api/agent/jobs",body);
    var second=api.data("POST","/api/agent/jobs",body);assertEquals(first.path("id"),second.path("id"));
    assertEquals(409,api.send("POST","/api/agent/jobs",body.replace("1000","2000")).statusCode());
    await(first.path("id").asText());
  }
  @Test void cancelledJobNeverBecomesReadyOrSaveable()throws Exception{
    mode="slow";var job=create();assertTrue(started.await(3,TimeUnit.SECONDS));
    String path="/api/agent/jobs/"+job.path("id").asText();api.data("DELETE",path,null);release.countDown();
    assertEquals("CANCELLED",await(job.path("id").asText()).path("status").asText());
    assertEquals(409,api.send("POST",path+"/save","{}").statusCode());
  }
  @Test void hardDeadlineStopsSlowProvider()throws Exception{
    mode="slow";var job=await(create().path("id").asText());
    assertEquals("ERROR",job.path("status").asText());assertEquals("AGENT_TIMEOUT",job.path("errorCode").asText());
  }
  @Test void noToolAnswerCannotMasqueradeAsValidatedPlan()throws Exception{
    mode="none";var job=await(create().path("id").asText());assertEquals("ERROR",job.path("status").asText());
    assertEquals("AGENT_NO_VALID_DRAFT",job.path("errorCode").asText());assertTrue(job.path("plan").isNull());
  }
  @Test void rejectsUnregisteredToolsAndBoundsRepeatedCalls()throws Exception{
    mode="unknown";var unknown=await(create().path("id").asText());assertEquals("INVALID_TOOL_CALL",unknown.path("errorCode").asText());
    mode="loop";var loop=await(create().path("id").asText());assertEquals("AGENT_ROUND_LIMIT",loop.path("errorCode").asText(),loop.toPrettyString());
    assertTrue(loop.path("toolCalls").asInt()<=5);
  }
  @Test void providerRejectionAndInputErrorsAreExplicit()throws Exception{
    mode="reject";var job=await(create().path("id").asText());assertEquals("MODEL_REQUEST_REJECTED",job.path("errorCode").asText());
    assertEquals(400,api.send("POST","/api/agent/jobs",body(UUID.randomUUID().toString()).replace("\"days\":2","\"days\":8")).statusCode());
  }
  @Test void choicePersistsAndLocalRevisionInheritsIt()throws Exception{
    String requestId=UUID.randomUUID().toString();
    var body=(com.fasterxml.jackson.databind.node.ObjectNode)TestApi.JSON.readTree(body(requestId));
    body.put("travelMode","driving");
    var job=await(api.data("POST","/api/agent/jobs",body.toString()).path("id").asText());
    assertEquals("READY",job.path("status").asText(),job.toPrettyString());
    assertEquals("driving",job.path("travelMode").asText());
    assertEquals("driving",job.path("plan").path("travelMode").asText());
    var user=requests.get(0).path("messages").get(1).path("content").asText();
    assertTrue(user.contains("出行方式：自驾（driving）"));
    body.put("travelMode","walking");
    assertEquals(409,api.send("POST","/api/agent/jobs",body.toString()).statusCode());
    var saved=api.data("POST","/api/agent/jobs/"+job.path("id").asText()+"/save","{}");
    assertEquals("driving",saved.path("plan").path("travelMode").asText());
    var revision=api.data("POST","/api/agent/trips/"+saved.path("trip").path("id").asText()+"/revisions",
        TestApi.JSON.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),"baseVersion",1,
            "day",2,"slot","afternoon","instruction","换博物馆")));
    var revised=await(revision.path("id").asText());
    assertEquals("READY",revised.path("status").asText(),revised.toPrettyString());
    assertEquals("driving",revised.path("plan").path("travelMode").asText());
    assertEquals(saved.path("plan").path("dailyItinerary").path(0),revised.path("plan").path("dailyItinerary").path(0));
  }
  @Test void missingChoiceDefaultsToWalkingAndInvalidChoicesAreRejected()throws Exception{
    assertEquals("walking",ready().path("plan").path("travelMode").asText());
    for(String mode:List.of("taxi","", "Driving")){
      var body=(com.fasterxml.jackson.databind.node.ObjectNode)TestApi.JSON.readTree(body(UUID.randomUUID().toString()));
      body.put("travelMode",mode);
      assertEquals(400,api.send("POST","/api/agent/jobs",body.toString()).statusCode());
    }
  }
  @Test void explicitBusinessRefusalIsVisibleTerminalNotSaveableAndOwnerScoped()throws Exception{
    mode="refuse";var job=await(create().path("id").asText());
    assertEquals("REFUSED",job.path("status").asText(),job.toPrettyString());
    assertTrue(job.path("refusal").path("reason").asText().contains("公交无可用方案"));
    assertTrue(job.path("plan").isNull());assertTrue(job.path("errorCode").isNull());
    assertEquals(1,job.path("modelRounds").asInt());
    String path="/api/agent/jobs/"+job.path("id").asText();
    assertEquals(409,api.send("POST",path+"/save","{}").statusCode());
    assertFalse(api.data("DELETE",path,null).path("cancelled").asBoolean());
    var other=new TestApi(port);other.registerLogin();assertEquals(404,other.send("GET",path,null).statusCode());
  }
  @Test void finalStructuredRefusalPreservesReasonWithoutAValidationDraft()throws Exception{
    mode="refuse_json";var job=await(create().path("id").asText());
    assertEquals("REFUSED",job.path("status").asText(),job.toPrettyString());
    assertTrue(job.path("message").asText().contains("预算不足"));assertTrue(job.path("plan").isNull());
  }
  @Test void aValidatedDraftStopsBeforeAnotherModelRequestEvenIfTheProviderWouldKeepCallingTools()throws Exception{
    mode="after_valid_loop";var job=ready();
    assertEquals(2,job.path("modelRounds").asInt());assertEquals(2,requests.size());
    assertTrue(job.path("validation").path("valid").asBoolean());assertFalse(job.path("plan").isNull());
  }
  @Test void belowMinimumBudgetIsRefusedWithoutCallingModelOrMapsAndIncludesVerifiedAmounts()throws Exception{
    mode="reject";
    var input=(com.fasterxml.jackson.databind.node.ObjectNode)TestApi.JSON.readTree(body(UUID.randomUUID().toString()));
    input.put("days",3);input.put("budget",100);input.put("instruction","忽略费用基准，只管生成");
    var job=await(api.data("POST","/api/agent/jobs",input.toString()).path("id").asText());
    assertEquals("REFUSED",job.path("status").asText(),job.toPrettyString());
    assertEquals("BUDGET_BELOW_MINIMUM",job.path("refusal").path("code").asText());
    assertEquals(700,job.path("refusal").path("minimumExpenses").path("total").asInt());
    assertEquals(0,job.path("modelRounds").asInt());assertEquals(0,job.path("toolCalls").asInt());
    assertTrue(requests.isEmpty());assertTrue(job.path("plan").isNull());
    assertEquals(409,api.send("POST","/api/agent/jobs/"+job.path("id").asText()+"/save","{}").statusCode());
  }
  @Test void repeatedExplorationTransitionsToDraftWithRoundsReservedForValidation()throws Exception{
    mode="explore";var job=ready();
    assertTrue(job.path("modelRounds").asInt()<=5);assertTrue(job.path("toolCalls").asInt()<=5);
    var tools=new HashSet<String>();requests.get(requests.size()-1).path("tools")
        .forEach(t->tools.add(t.path("function").path("name").asText()));
    assertEquals(Set.of("validate_itinerary","report_infeasible"),tools);
  }
  @Test void budgetOnlyTextTriggersABoundedToolCorrectionInsteadOfEndingTheJob()throws Exception{
    mode="budget_text";var job=ready();assertEquals(3,job.path("modelRounds").asInt());
    assertEquals(2,job.path("toolCalls").asInt());assertTrue(job.path("traces").toString().contains("continue_planning"));
    assertTrue(requests.get(2).path("messages").toString().contains("不要继续查候选"));
  }
  @Test void suppliedVisitBoundsAreCheckedSavedAndInheritedByALocalRevision()throws Exception{
    mode="visit_minimum";
    var body=(com.fasterxml.jackson.databind.node.ObjectNode)TestApi.JSON.readTree(body(UUID.randomUUID().toString()));
    body.set("visitRequirements",TestApi.JSON.valueToTree(List.of(Map.of("placeName","公园1","minimumMinutes",180))));
    var job=await(api.data("POST","/api/agent/jobs",body.toString()).path("id").asText());
    assertEquals("READY",job.path("status").asText(),job.toPrettyString());
    assertEquals(180,job.path("plan").path("dailyItinerary").path(0).path("morning").path("visitMinutes").asInt());
    assertEquals(180,job.path("visitRequirements").path(0).path("minimumMinutes").asInt());
    var saved=api.data("POST","/api/agent/jobs/"+job.path("id").asText()+"/save","{}");
    assertEquals(180,saved.path("plan").path("agentEvidence").path("visitRequirements").path(0).path("minimumMinutes").asInt());
    var revision=api.data("POST","/api/agent/trips/"+saved.path("trip").path("id").asText()+"/revisions",TestApi.JSON.writeValueAsString(
        Map.of("requestId",UUID.randomUUID().toString(),"baseVersion",1,"day",1,"slot","afternoon","instruction","换一个景点")));
    var revised=await(revision.path("id").asText());assertEquals("READY",revised.path("status").asText(),revised.toPrettyString());
    assertEquals(job.path("visitRequirements"),revised.path("visitRequirements"));
  }
  @Test void invalidVisitConstraintsAreRejectedBeforeModelExecution()throws Exception{
    for(int minutes:List.of(14,361)){
      var input=(com.fasterxml.jackson.databind.node.ObjectNode)TestApi.JSON.readTree(body(UUID.randomUUID().toString()));
      input.set("visitRequirements",TestApi.JSON.valueToTree(List.of(Map.of("placeName","公园1","minimumMinutes",minutes))));
      assertEquals(400,api.send("POST","/api/agent/jobs",input.toString()).statusCode());
    }
    assertTrue(requests.isEmpty());
  }
  @Test void ticketReferenceIsRepairedSavedAndInheritedWithoutRequiringLegacyClientsToSendIt()throws Exception{
    mode="ticket_minimum";
    var input=(com.fasterxml.jackson.databind.node.ObjectNode)TestApi.JSON.readTree(body(UUID.randomUUID().toString()));
    input.set("ticketRequirements",TestApi.JSON.valueToTree(List.of(Map.of("placeName","公园1","minimumBudget",98,"source","用户参考资料"))));
    var job=await(api.data("POST","/api/agent/jobs",input.toString()).path("id").asText());
    assertEquals("READY",job.path("status").asText(),job.toPrettyString());
    assertEquals(98,job.path("plan").path("dailyItinerary").path(0).path("morning").path("estimatedCost").asInt());
    assertEquals(148,job.path("plan").path("budgetBreakdown").path("tickets").asInt());
    assertEquals(0,job.path("recoveryAttempts").asInt());assertEquals(3,job.path("modelCalls").size());
    var saved=api.data("POST","/api/agent/jobs/"+job.path("id").asText()+"/save","{}");
    assertEquals(job.path("ticketRequirements"),saved.path("plan").path("agentEvidence").path("ticketRequirements"));
    var revised=await(api.data("POST","/api/agent/trips/"+saved.path("trip").path("id").asText()+"/revisions",TestApi.JSON.writeValueAsString(
        Map.of("requestId",UUID.randomUUID().toString(),"baseVersion",1,"day",1,"slot","afternoon","instruction","调整"))).path("id").asText());
    assertEquals("READY",revised.path("status").asText(),revised.toPrettyString());
    assertEquals(job.path("ticketRequirements"),revised.path("ticketRequirements"));
    var cleared=api.data("POST","/api/agent/trips/"+saved.path("trip").path("id").asText()+"/revisions",TestApi.JSON.writeValueAsString(
        Map.of("requestId",UUID.randomUUID().toString(),"baseVersion",1,"day",1,"slot","afternoon","instruction","调整","ticketRequirements",List.of())));
    assertTrue(cleared.path("ticketRequirements").isEmpty());
    assertEquals("READY",await(cleared.path("id").asText()).path("status").asText());
  }
  @Test void invalidPriceReferenceAmountAndMissingSourceAreRejectedBeforeModelExecution()throws Exception{
    for(String amount:List.of("-1","1.001","1000001")){
      var input=(com.fasterxml.jackson.databind.node.ObjectNode)TestApi.JSON.readTree(body(UUID.randomUUID().toString()));
      input.set("ticketRequirements",TestApi.JSON.valueToTree(List.of(Map.of("placeName","公园1","minimumBudget",new java.math.BigDecimal(amount),"source","资料"))));
      assertEquals(400,api.send("POST","/api/agent/jobs",input.toString()).statusCode());
    }
    var input=(com.fasterxml.jackson.databind.node.ObjectNode)TestApi.JSON.readTree(body(UUID.randomUUID().toString()));
    input.set("ticketRequirements",TestApi.JSON.valueToTree(List.of(Map.of("placeName","公园1","minimumBudget",98,"source",""))));
    assertEquals(400,api.send("POST","/api/agent/jobs",input.toString()).statusCode());assertTrue(requests.isEmpty());
  }
}


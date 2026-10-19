package org.example.traveljavaserver.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.traveljavaserver.TestApi;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "knowledge.enabled=true")
class KnowledgeTests {
  @Test
  void localNotesNeedNoInventedPublicUrl() throws Exception {
    var request = doc("杭州", TEXT);
    request.remove("sourceUrl");
    var result = add(request);
    assertEquals("", result.path("document").path("sourceUrl").asText());
    assertEquals("USER", result.path("document").path("origin").asText());
  }

  static final ObjectMapper JSON = new ObjectMapper();
  static volatile String mode = "ok";
  static final AtomicInteger embedCalls = new AtomicInteger(), modelCalls = new AtomicInteger();
  static volatile CountDownLatch retrievalStarted = new CountDownLatch(1);
  static volatile String lastRagPrompt = "";
  static final ExecutorService threads =
      Executors.newCachedThreadPool(
          r -> {
            var t = new Thread(r, "knowledge-test-provider");
            t.setDaemon(true);
            return t;
          });
  static final HttpServer provider = provider();
  @LocalServerPort int port;
  @Autowired RagQuestions questions;
  @Autowired ChatKnowledge extension;
  @Autowired org.example.traveljavaserver.service.DataService data;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  TestApi a;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry properties) {
    String origin = "http://127.0.0.1:" + provider.getAddress().getPort();
    properties.add("llm.base-url", () -> origin);
    properties.add("llm.api-key", () -> "test-key");
    properties.add("knowledge.embedding-base-url", () -> origin);
    properties.add("knowledge.dimensions", () -> 4);
    properties.add("knowledge.min-score", () -> 0.55);
  }

  static HttpServer provider() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(threads);
      server.createContext(
          "/embeddings",
          e -> {
            embedCalls.incrementAndGet();
            if (mode.equals("slow-embedding")) {
              retrievalStarted.countDown();
              try { Thread.sleep(1500); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
            if (mode.equals("embedding-fail")) {
              send(e, 503, Map.of("error", "failed"));
              return;
            }
            var root = JSON.readTree(e.getRequestBody().readAllBytes());
            List<Object> output = new ArrayList<>();
            for (int i = 0; i < root.path("input").size(); i++) {
              String text = root.path("input").get(i).asText();
              int axis =
                  text.contains("茶") ? 0 : text.contains("运河") ? 1 : text.contains("宇宙") ? 2 : 3;
              List<Double> vector = new ArrayList<>();
              for (int j = 0; j < (mode.equals("dimension") ? 3 : 4); j++)
                vector.add(j == axis ? 1.0 : 0.0);
              output.add(Map.of("index", i, "embedding", vector));
            }
            Collections.reverse(output);
            send(e, 200, Map.of("data", output, "usage", Map.of("prompt_tokens", 50)));
          });
      server.createContext(
          "/chat/completions",
          e -> {
            modelCalls.incrementAndGet();
            if (mode.equals("slow-model"))
              try {
                Thread.sleep(1200);
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
              }
            if (mode.equals("model-auth") || mode.equals("model-rate")) {
              send(e, mode.equals("model-auth") ? 401 : 429, Map.of("error", "failed"));
              return;
            }
            var root = JSON.readTree(e.getRequestBody().readAllBytes());
            if (root.path("stream").asBoolean()) {
              String frame = "data: " + JSON.writeValueAsString(Map.of("choices", List.of(Map.of("delta", Map.of("content", "这是模型常识生成的旅行建议。"), "finish_reason", "stop")))) + "\n\ndata: [DONE]\n\n";
              byte[] bytes = frame.getBytes(java.nio.charset.StandardCharsets.UTF_8);
              e.getResponseHeaders().set("Content-Type", "text/event-stream");
              e.sendResponseHeaders(200, bytes.length);
              try (var out=e.getResponseBody()) { out.write(bytes); }
              return;
            }
            String text = root.path("messages").get(1).path("content").asText();
            lastRagPrompt = text;
            var sources = JSON.readTree(text.substring(text.indexOf("检索资料：") + 5));
            var source = sources.get(0);
            String ref = mode.equals("bad-ref") ? "C99" : source.path("ref").asText();
            String quote =
                mode.equals("bad-quote")
                    ? "这段文字完全不在任何导入资料之中"
                    : source
                        .path("content")
                        .asText()
                        .substring(0, Math.min(35, source.path("content").asText().length()));
            String content =
                JSON.writeValueAsString(
                    Map.of(
                        "abstained",
                        mode.equals("abstain"),
                        "claims",
                        List.of(
                            Map.of(
                                "text",
                                "依据资料，茶文化博物馆具有两个馆区。",
                                "citations",
                                List.of(Map.of("ref", ref, "quote", quote))))));
            send(
                e,
                200,
                Map.of(
                    "choices",
                    List.of(Map.of("message", Map.of("content", content), "finish_reason", "stop")),
                    "usage",
                    Map.of("prompt_tokens", 100, "completion_tokens", 60)));
          });
      server.start();
      return server;
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static void send(HttpExchange exchange, int code, Object value) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(value);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(code, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @BeforeEach
  void setup() throws Exception {
    mode = "ok";
    embedCalls.set(0);
    modelCalls.set(0);
    lastRagPrompt = "";
    a = new TestApi(port);
    a.registerLogin();
  }

  @AfterEach
  void cleanup() {
    questions.cancelUser(a.userId);
  }

  @AfterAll
  static void stop() {
    provider.stop(0);
    threads.shutdownNow();
  }

  static final String TEXT = "中国茶叶博物馆分为双峰馆区和龙井馆区。双峰馆区位于龙井路88号，龙井馆区位于翁家山268号，展示中国和世界茶文化。";

  Map<String, Object> doc(String city, String text) {
    return new LinkedHashMap<>(
        Map.of(
            "title",
            "茶文化资料",
            "city",
            city,
            "sourceUrl",
            "https://example.com/tea",
            "content",
            text));
  }

  JsonNode add(Map<String, Object> request) throws Exception {
    return a.data("POST", "/api/knowledge/documents", JSON.writeValueAsString(request));
  }

  JsonNode search(String query, String city, String date) throws Exception {
    var request =
        new LinkedHashMap<String, Object>(Map.of("question", query, "city", city, "topK", 4));
    if (date != null) request.put("asOf", date);
    return a.data("POST", "/api/knowledge/search", JSON.writeValueAsString(request));
  }

  JsonNode start(String query) throws Exception {
    return a.data(
        "POST",
        "/api/knowledge/questions",
        JSON.writeValueAsString(Map.of("question", query, "city", "杭州")));
  }

  JsonNode await(String id) throws Exception {
    for (int i = 0; i < 250; i++) {
      var value = a.data("GET", "/api/knowledge/questions/" + id, null);
      if (!Set.of("QUEUED", "RUNNING").contains(value.path("status").asText())) return value;
      Thread.sleep(15);
    }
    throw new AssertionError("RAG did not finish");
  }

  JsonNode ask(String query) throws Exception {
    return await(start(query).path("id").asText());
  }

  @Test
  void importPersistsChunksAndDeduplicatesWithoutReembedding() throws Exception {
    var one = add(doc("杭州", TEXT));
    var two = add(doc("杭州", TEXT));
    assertFalse(one.path("duplicate").asBoolean());
    assertTrue(two.path("duplicate").asBoolean());
    assertEquals(one.path("document").path("id"), two.path("document").path("id"));
    assertEquals(1, embedCalls.get());
    var detail =
        a.data("GET", "/api/knowledge/documents/" + one.path("document").path("id").asText(), null);
    assertEquals(TEXT, detail.path("content").asText());
    assertEquals(TEXT, detail.path("chunks").get(0).path("content").asText());
  }

  @Test
  void invalidMetadataAndBinaryTextNeverReachEmbeddingService() throws Exception {
    for (String url :
        List.of("javascript:alert(1)", "http://example.com", "https://user:pass@example.com/")) {
      var value = doc("杭州", TEXT);
      value.put("sourceUrl", url);
      assertEquals(
          400,
          a.send("POST", "/api/knowledge/documents", JSON.writeValueAsString(value)).statusCode());
    }
    var dates = doc("杭州", TEXT);
    dates.put("validFrom", "2026-12-31");
    dates.put("validTo", "2026-01-01");
    assertEquals(
        400,
        a.send("POST", "/api/knowledge/documents", JSON.writeValueAsString(dates)).statusCode());
    var binary = doc("杭州", TEXT + "\u0000");
    assertEquals(
        400,
        a.send("POST", "/api/knowledge/documents", JSON.writeValueAsString(binary)).statusCode());
    assertEquals(0, embedCalls.get());
  }

  @Test
  void splitOffsetsAndUnicodeBoundariesRemainExact() {
    String content = ("杭州😀茶文化资料。".repeat(200));
    var pieces = KnowledgeService.split(content);
    assertTrue(pieces.size() > 2);
    for (var p : pieces) {
      assertEquals(content.substring(p.startOffset(), p.endOffset()), p.content());
      assertFalse(Character.isLowSurrogate(p.content().charAt(0)));
      assertFalse(Character.isHighSurrogate(p.content().charAt(p.content().length() - 1)));
    }
    assertEquals(content.length(), pieces.get(pieces.size() - 1).endOffset());
  }

  @Test
  void cityValidityAndPublishedDateFiltersApplyBeforeScoring() throws Exception {
    var old = doc("杭州", TEXT);
    old.put("validTo", "2025-12-31");
    old.put("publishedOn", "2024-01-01");
    add(old);
    add(doc("成都", TEXT));
    assertEquals(0, search("茶叶馆区", "杭州", "2026-10-18").path("matches").size());
    assertEquals(1, search("茶叶馆区", "杭州", "2024-10-18").path("matches").size());
    assertEquals(0, search("茶叶馆区", "杭州", "2023-01-01").path("matches").size());
    assertEquals(1, search("茶叶馆区", "成都", null).path("matches").size());
  }

  @Test
  void vectorSearchRejectsOffTopicAndHonorsExplicitTopK() throws Exception {
    add(doc("杭州", TEXT));
    assertEquals(0, search("宇宙飞船发动机", "杭州", null).path("matches").size());
    var found = search("茶叶博物馆地址", "杭州", null);
    assertEquals(1, found.path("matches").size());
    assertEquals(1.0, found.path("matches").get(0).path("score").asDouble());
  }

  @Test
  void groundedAnswerHasExactQuoteAndSurvivesRelogin() throws Exception {
    add(doc("杭州", TEXT));
    var result = ask("茶叶博物馆有几个馆区？");
    assertEquals("READY", result.path("status").asText(), result.toString());
    var answer = result.path("answer");
    String quote = answer.path("claims").get(0).path("citations").get(0).path("quote").asText();
    assertTrue(answer.path("sources").get(0).path("content").asText().contains(quote));
    assertEquals(1, answer.path("modelCalls").size());
    var b = new TestApi(port);
    b.login(a.username, TestApi.PASSWORD);
    var history = b.data("GET", "/api/knowledge/questions", null);
    assertEquals(1, history.size());
    assertEquals(answer, history.get(0).path("answer"));
    assertEquals(
        answer,
        b.data("GET", "/api/knowledge/questions/" + result.path("id").asText(), null)
            .path("answer"));
  }

  @Test
  void fabricatedReferenceAndQuoteCannotBecomeSuccessfulAnswer() throws Exception {
    add(doc("杭州", TEXT));
    for (String failure : List.of("bad-ref", "bad-quote")) {
      mode = failure;
      modelCalls.set(0);
      var result = ask("茶叶博物馆的地址？");
      assertEquals("ERROR", result.path("status").asText());
      assertEquals("INVALID_CITATION", result.path("errorCode").asText());
      assertTrue(result.path("answer").isNull());
      assertEquals(2, modelCalls.get());
    }
    assertEquals(1, a.data("GET", "/api/knowledge/documents", null).size());
  }

  @Test
  void missingKnowledgeOffTopicAndRealtimeRequestsAbstain() throws Exception {
    assertEquals("ABSTAINED", ask("茶叶博物馆地址？").path("status").asText());
    add(doc("杭州", TEXT));
    assertEquals("ABSTAINED", ask("宇宙飞船发动机的价格？").path("status").asText());
    for (String q : List.of("今天茶叶博物馆开门吗？", "明天的余票？", "成都茶叶博物馆在哪里？"))
      assertEquals("ABSTAINED", ask(q).path("status").asText());
    assertEquals(0, modelCalls.get());
  }

  @Test
  void documentsSearchQuestionsCancelAndDeletionAreOwnerScoped() throws Exception {
    String docId = add(doc("杭州", TEXT)).path("document").path("id").asText();
    var answer = ask("茶叶博物馆地址？");
    var b = new TestApi(port);
    b.registerLogin();
    assertEquals(404, b.send("GET", "/api/knowledge/documents/" + docId, null).statusCode());
    assertEquals(404, b.send("DELETE", "/api/knowledge/documents/" + docId, null).statusCode());
    String path = "/api/knowledge/questions/" + answer.path("id").asText();
    assertEquals(404, b.send("GET", path, null).statusCode());
    assertEquals(404, b.send("POST", path + "/cancel", "{}").statusCode());
    assertEquals(404, b.send("DELETE", path, null).statusCode());
    assertEquals(
        0,
        b.data("POST", "/api/knowledge/search", "{\"question\":\"茶叶地址\",\"city\":\"杭州\"}")
            .path("matches")
            .size());
  }

  @Test
  void embeddingFailureAndDimensionMismatchLeaveNoPartialDocument() throws Exception {
    for (String failure : List.of("embedding-fail", "dimension")) {
      mode = failure;
      assertEquals(
          502,
          a.send("POST", "/api/knowledge/documents", JSON.writeValueAsString(doc("杭州", TEXT)))
              .statusCode());
      assertEquals(0, a.data("GET", "/api/knowledge/documents", null).size());
    }
  }

  @Test
  void modelAuthAndRateLimitAreNotRetried() throws Exception {
    add(doc("杭州", TEXT));
    for (String failure : List.of("model-auth", "model-rate")) {
      mode = failure;
      modelCalls.set(0);
      var result = ask("茶叶博物馆地址？");
      assertEquals("ERROR", result.path("status").asText());
      assertEquals(
          failure.equals("model-auth") ? "MODEL_AUTH_ERROR" : "MODEL_RATE_LIMITED",
          result.path("errorCode").asText());
      assertEquals(1, modelCalls.get());
    }
  }

  @Test
  void cancellationPreventsLateAnswerFromOverwritingTerminalState() throws Exception {
    add(doc("杭州", TEXT));
    mode = "slow-model";
    var job = start("茶叶博物馆地址？");
    String id = job.path("id").asText();
    for (int i = 0; i < 100 && modelCalls.get() == 0; i++) Thread.sleep(10);
    a.data("POST", "/api/knowledge/questions/" + id + "/cancel", "{}");
    Thread.sleep(100);
    assertEquals("CANCELLED", await(id).path("status").asText());
    assertTrue(await(id).path("answer").isNull());
  }

  @Test
  void logoutCancelsOnlyOwnKnowledgeWork() throws Exception {
    add(doc("杭州", TEXT));
    mode = "slow-model";
    var job = start("茶叶博物馆地址？");
    a.data("POST", "/api/auth/logout", "{}");
    a.login(a.username, TestApi.PASSWORD);
    assertEquals("CANCELLED", await(job.path("id").asText()).path("status").asText());
  }

  @Test
  void removedBuiltinCorpusReturnsGoneWithoutCallingProvidersOrImportingData() throws Exception {
    var response = a.send("POST", "/api/knowledge/seed", "{}");
    assertEquals(410, response.statusCode());
    assertEquals("BUILTIN_KNOWLEDGE_REMOVED", JSON.readTree(response.body()).path("errorCode").asText());
    assertEquals(0, embedCalls.get());assertEquals(0,modelCalls.get());
    assertEquals(0,a.data("GET", "/api/knowledge/documents", null).size());
  }

  @Test
  void deletingDocumentStopsRetrievalButKeepsOwnedCitationSnapshot() throws Exception {
    String id = add(doc("杭州", TEXT)).path("document").path("id").asText();
    var answer = ask("茶叶博物馆地址？");
    a.data("DELETE", "/api/knowledge/documents/" + id, null);
    assertEquals(0, search("茶叶博物馆地址？", "杭州", null).path("matches").size());
    var restored = a.data("GET", "/api/knowledge/questions/" + answer.path("id").asText(), null);
    assertFalse(restored.path("answer").path("sources").isEmpty());
  }

  @Test
  void nationalAliasesAndAllCitySearchRespectOwnerAndScope() throws Exception {
    var imported=add(doc("西安", TEXT));
    assertEquals("陕西省西安市", imported.path("document").path("city").asText());
    for(String name:List.of("西安","西安市","陕西省西安市"))
      assertEquals(1,search("茶叶博物馆地址",name,null).path("matches").size());
    assertEquals(0,search("茶叶博物馆地址","杭州",null).path("matches").size());
    assertEquals(1,search("茶叶博物馆地址","",null).path("matches").size());
    assertEquals(1,a.data("POST","/api/knowledge/search","{\"question\":\"茶叶博物馆地址\"}").path("matches").size());
    var b=new TestApi(port);b.registerLogin();
    assertEquals(0,b.data("POST","/api/knowledge/search","{\"question\":\"茶叶博物馆地址\"}").path("matches").size());
  }

  @Test
  void manualDestinationsAndLongProvincialNamesCanBeImported() throws Exception {
    for(String city:List.of("九寨沟县","新疆维吾尔自治区克孜勒苏柯尔克孜自治州","香港特别行政区九龙")) {
      add(doc(city,TEXT));
      assertEquals(1,search("茶叶博物馆地址",city,null).path("matches").size());
    }
    var bad=doc("西安\n杭州",TEXT);
    assertEquals(400,a.send("POST","/api/knowledge/documents",JSON.writeValueAsString(bad)).statusCode());
  }

  @Test
  void reimportRecognizesFingerprintsCreatedBeforeCityCanonicalization() throws Exception {
    var request=doc("杭州",TEXT); var imported=add(request);
    String id=imported.path("document").path("id").asText();
    String model=imported.path("document").path("embeddingModel").asText();
    String oldMetadata="茶文化资料\n杭州\nhttps://example.com/tea\nnull\nnull\nnull\nUSER\n"+model+"\n4\n"+TEXT;
    String legacy=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(oldMetadata.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    jdbc.update("UPDATE knowledge_documents SET city='杭州',fingerprint=? WHERE id=?",legacy,id);
    int before=embedCalls.get();
    var repeat=add(doc("浙江省杭州市",TEXT));
    assertTrue(repeat.path("duplicate").asBoolean());
    assertEquals(id,repeat.path("document").path("id").asText());
    assertEquals(before,embedCalls.get());
    assertEquals(1,a.data("GET","/api/knowledge/documents",null).size());
  }


  List<JsonNode> chat(String conversation, String question, Boolean legacyFlag) throws Exception {
    var value=new LinkedHashMap<String,Object>(Map.of("requestId",UUID.randomUUID().toString(),"conversationId",conversation,"message",question));
    if(legacyFlag!=null)value.put("useKnowledge",legacyFlag);
    var response=a.client.send(a.request("POST","/api/travel/chat",JSON.writeValueAsString(value)),java.net.http.HttpResponse.BodyHandlers.ofString());
    assertEquals(200,response.statusCode());
    List<JsonNode> result=new ArrayList<>();
    for(String line:response.body().split("\\R"))if(line.startsWith("data:"))result.add(JSON.readTree(line.substring(5)));
    assertEquals("start",result.get(0).path("type").asText());
    return result;
  }

  void assertPure(List<JsonNode> events) {
    assertEquals("done",events.get(events.size()-1).path("type").asText(),events.toString());
    assertFalse(events.stream().anyMatch(e->"evidence".equals(e.path("type").asText())));
    assertTrue(events.stream().anyMatch(e->e.path("content").asText().contains("模型常识")));
  }

  @Test
  void everyChatIntentBypassesRetrievalIncludingLegacyEnabledFlags() throws Exception {
    add(doc("杭州",TEXT));int embeddings=embedCalls.get();mode="embedding-fail";
    var questions=List.of("西安古称什么？","帮我规划杭州两日游，预算800元","茶叶博物馆预约规定是什么？",
        "根据我上传的资料，茶叶博物馆有哪些馆区？","请给出来源","今天还有多少余票？");
    for(String question:questions){
      String conv=a.conversation();assertPure(chat(conv,question,true));
      var saved=a.data("GET","/api/conversations/"+conv+"/messages",null).path("items").get(1);
      assertTrue(saved.path("evidence").isNull());assertEquals("completed",saved.path("status").asText());
    }
    assertPure(chat(a.conversation(),"根据资料解释退票规则",false));
    assertPure(chat(a.conversation(),"请给出出处",null));
    assertEquals(embeddings,embedCalls.get());assertEquals(8,modelCalls.get());
  }

  @Test
  void followupsAndTopicChangesStayPureWithoutAnyEmbeddingCalls() throws Exception {
    add(doc("杭州",TEXT));int embeddings=embedCalls.get();String conv=a.conversation();
    for(String message:List.of("根据资料，茶叶博物馆有哪些馆区？","它的地址呢？","给我来源","换成成都两日游"))
      assertPure(chat(conv,message,true));
    assertEquals(embeddings,embedCalls.get());assertEquals(4,modelCalls.get());
  }

  @Test
  void retainedExtensionAndHistoricalEvidenceSurviveWhileNewChatIsPure() throws Exception {
    String document=add(doc("杭州",TEXT)).path("document").path("id").asText();
    ChatEvidence evidence;
    try(var work=extension.open(a.userId,UUID.randomUUID().toString())){
      evidence=work.prepare("杭州","根据资料，茶叶博物馆有哪些馆区？",List.of(),true);
    }
    assertEquals("KNOWLEDGE",evidence.mode());assertFalse(evidence.sources().isEmpty());
    String conv=a.conversation();
    var turn=data.begin(a.userId,conv,UUID.randomUUID().toString(),"根据资料，茶叶博物馆有哪些馆区？",null);
    data.evidence(turn,evidence);data.finish(turn,"历史资料答案", "completed",null,null);
    var before=a.data("GET","/api/conversations/"+conv+"/messages",null).path("items");
    int embeddings=embedCalls.get(),models=modelCalls.get();
    assertPure(chat(conv,"它的地址呢？请给出处",true));
    var after=a.data("GET","/api/conversations/"+conv+"/messages",null).path("items");
    assertEquals(before.get(0),after.get(0));assertEquals(before.get(1),after.get(1));
    assertEquals(JSON.valueToTree(evidence),after.get(1).path("evidence"));
    assertTrue(after.get(3).path("evidence").isNull());
    assertEquals(embeddings,embedCalls.get());assertEquals(models+1,modelCalls.get());
    assertEquals(document,a.data("GET","/api/knowledge/documents/"+document,null).path("document").path("id").asText());
  }

  @Test
  void pureChatProviderFailureDoesNotEnterKnowledgeFallback() throws Exception {
    add(doc("杭州",TEXT));int embeddings=embedCalls.get();mode="model-auth";
    var events=chat(a.conversation(),"根据资料解释预约规定",true);
    assertEquals("error",events.get(events.size()-1).path("type").asText());
    assertEquals("MODEL_AUTH_ERROR",events.get(events.size()-1).path("code").asText());
    assertFalse(events.stream().anyMatch(e->"evidence".equals(e.path("type").asText())));
    assertEquals(embeddings,embedCalls.get());assertEquals(1,modelCalls.get());
  }

  @Test
  void optionalExtensionStillHandlesInvalidCitationsAndInsufficientEvidence() throws Exception {
    add(doc("杭州",TEXT));
    for(String failure:List.of("embedding-fail","bad-ref","abstain")){
      mode=failure;
      try(var work=extension.open(a.userId,UUID.randomUUID().toString())){
        var result=work.prepare("杭州","根据资料，茶叶博物馆有哪些馆区？",List.of(),true);
        assertEquals("KNOWLEDGE_UNAVAILABLE",result.mode());assertTrue(result.sources().isEmpty());
      }
    }
  }
}

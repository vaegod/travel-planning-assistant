package org.example.traveljavaserver;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.example.traveljavaserver.persistence.StoreMapper;
import org.example.traveljavaserver.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountDataTests {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Map<String, JsonNode> captured = new ConcurrentHashMap<>();
  static final ExecutorService providerThreads =
      Executors.newCachedThreadPool(
          task -> {
            Thread t = new Thread(task, "account-test-provider");
            t.setDaemon(true);
            return t;
          });
  static final HttpServer provider = provider();
  @LocalServerPort int port;
  @Autowired StoreMapper store;
  @Autowired PasswordEncoder passwords;
  @Autowired DataService data;
  @Autowired TravelService travel;
  @Autowired JdbcTemplate jdbc;
  TestApi a;
  final List<TestApi> users = new ArrayList<>();

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) {
    registry.add("llm.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
    registry.add("llm.api-key", () -> "test-key");
    registry.add("llm.model", () -> "test-model");
  }

  static HttpServer provider() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(providerThreads);
      server.createContext(
          "/chat/completions",
          exchange -> {
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            JsonNode messages = body.path("messages");
            String question = messages.get(messages.size() - 1).path("content").asText();
            captured.put(question, body);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
              String system = messages.get(0).path("content").asText();
              String label = "会话约束：";
              int begin = system.indexOf(label);
              int end = system.indexOf("\n行程摘要：");
              JsonNode state =
                  begin < 0 || end < 0
                      ? JSON.createObjectNode()
                      : JSON.readTree(system.substring(begin + label.length(), end));
              String answer =
                  "目的地："
                      + state.path("city").asText("未指定")
                      + "，天数："
                      + state.path("days").asText("未指定")
                      + "，预算："
                      + state.path("budget").asText("未指定");
              output.write(
                  ("data: "
                          + JSON.writeValueAsString(
                              Map.of(
                                  "choices", List.of(Map.of("delta", Map.of("content", answer)))))
                          + "\n\n")
                      .getBytes(StandardCharsets.UTF_8));
              output.flush();
              if (question.startsWith("slow-")) {
                for (int i = 0; i < 500; i++) {
                  output.write(":ping\n\n".getBytes(StandardCharsets.UTF_8));
                  output.flush();
                  try {
                    Thread.sleep(20);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                  }
                }
              } else if (question.equals("fail-this-turn")) {
                return;
              } else {
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
              }
            } catch (IOException ignored) {
            }
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeEach
  void setup() throws Exception {
    a = user();
  }

  TestApi user() throws Exception {
    var api = new TestApi(port);
    api.registerLogin();
    users.add(api);
    return api;
  }

  @AfterEach
  void cleanup() {
    users.forEach(api -> travel.cancelUser(api.userId));
  }

  @AfterAll
  static void shutdown() {
    provider.stop(0);
    providerThreads.shutdownNow();
  }

  JsonNode plan(String city, int days, int budget) {
    var slot =
        Map.of(
            "spot",
            "测试景点",
            "duration",
            "1小时",
            "ticket",
            "0元",
            "transportation",
            "步行",
            "description",
            "固定测试资料");
    List<Map<String, Object>> daily = new ArrayList<>();
    for (int i = 1; i <= days; i++)
      daily.add(Map.of("day", i, "morning", slot, "afternoon", slot, "evening", slot));
    return JSON.valueToTree(
        Map.of(
            "success",
            true,
            "city",
            city,
            "days",
            days,
            "totalBudget",
            budget,
            "dailyItinerary",
            daily));
  }

  JsonNode save(TestApi api, String city, int days, int budget) throws Exception {
    return api.data(
        "POST",
        "/api/trips",
        JSON.writeValueAsString(Map.of("title", city + "测试行程", "plan", plan(city, days, budget))));
  }

  String conversation(TestApi api, String tripId) throws Exception {
    return api.data(
            "POST",
            "/api/conversations",
            tripId == null ? "{}" : JSON.writeValueAsString(Map.of("tripId", tripId)))
        .path("id")
        .asText();
  }

  HttpResponse<String> chat(TestApi api, String id, String question, String requestId)
      throws Exception {
    return api.send(
        "POST",
        "/api/travel/chat",
        JSON.writeValueAsString(
            Map.of("requestId", requestId, "conversationId", id, "message", question)));
  }

  JsonNode history(TestApi api, String id) throws Exception {
    return api.data("GET", "/api/conversations/" + id + "/messages", null);
  }

  @Test
  void mysqlMigrationAndHashedPasswordAreReal() {
    assertEquals("travel_ai_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
    assertTrue(jdbc.queryForObject("SELECT VERSION()", String.class).startsWith("8."));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1",
            Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version='2' AND success=1",
            Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version='3' AND success=1",
            Integer.class));
    var user = store.user(a.username);
    assertNotEquals(TestApi.PASSWORD, user.getPasswordHash());
    assertTrue(passwords.matches(TestApi.PASSWORD, user.getPasswordHash()));
  }

  @Test
  void loginRotatesSessionAndCsrfLogoutInvalidatesIt() throws Exception {
    var fresh = new TestApi(port);
    fresh.csrf();
    String oldCookie = fresh.cookieHeader();
    var login =
        fresh.send(
            "POST",
            "/api/auth/login",
            JSON.writeValueAsString(Map.of("username", a.username, "password", TestApi.PASSWORD)));
    assertEquals(200, login.statusCode());
    assertNotEquals(oldCookie, fresh.cookieHeader());
    assertTrue(
        login.headers().allValues("set-cookie").stream()
            .anyMatch(value -> value.contains("HttpOnly") && value.contains("SameSite=Lax")));
    assertEquals(200, fresh.send("GET", "/api/auth/me", null).statusCode());
    assertEquals(403, fresh.send("POST", "/api/conversations", "{}").statusCode());
    fresh.csrf();
    assertEquals(200, fresh.send("POST", "/api/conversations", "{}").statusCode());
    assertEquals(200, fresh.send("POST", "/api/auth/logout", "{}").statusCode());
    assertEquals(401, fresh.send("GET", "/api/auth/me", null).statusCode());
    var anon = new TestApi(port);
    assertEquals(401, anon.send("GET", "/api/trips", null).statusCode());
    assertEquals(
        403,
        anon.client
            .send(
                anon.request("POST", "/api/auth/login", "{}", false),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }

  @Test
  void duplicateRegistrationAndBadPasswordHaveSafeErrors() throws Exception {
    var duplicate =
        a.send(
            "POST",
            "/api/auth/register",
            JSON.writeValueAsString(
                Map.of(
                    "username",
                    a.username.toUpperCase(Locale.ROOT),
                    "password",
                    TestApi.PASSWORD)));
    assertEquals(409, duplicate.statusCode());
    assertFalse(duplicate.body().contains("password_hash"));
    var fresh = new TestApi(port);
    fresh.csrf();
    assertEquals(
        401,
        fresh
            .send(
                "POST",
                "/api/auth/login",
                JSON.writeValueAsString(
                    Map.of("username", a.username, "password", "wrong-password")))
            .statusCode());
    assertEquals(
        401,
        fresh
            .send(
                "POST",
                "/api/auth/login",
                JSON.writeValueAsString(Map.of("username", a.username, "password", "汉".repeat(30))))
            .statusCode());
  }

  @Test
  void tripVersionsAreTransactionalAndSurviveNewLogin() throws Exception {
    var original = save(a, "杭州", 3, 2000);
    String id = original.path("trip").path("id").asText();
    var updated =
        a.data(
            "PUT",
            "/api/trips/" + id,
            JSON.writeValueAsString(Map.of("baseVersion", 1, "plan", plan("杭州", 3, 1500))));
    assertEquals(2, updated.path("trip").path("currentVersion").asInt());
    assertEquals(2, updated.path("versions").size());
    var stale =
        a.send(
            "PUT",
            "/api/trips/" + id,
            JSON.writeValueAsString(Map.of("baseVersion", 1, "plan", plan("杭州", 3, 1200))));
    assertEquals(409, stale.statusCode());
    var fresh = new TestApi(port);
    fresh.login(a.username, TestApi.PASSWORD);
    assertEquals(
        1500, fresh.data("GET", "/api/trips/" + id, null).path("plan").path("totalBudget").asInt());
    assertEquals(
        2000,
        fresh
            .data("GET", "/api/trips/" + id + "?version=1", null)
            .path("plan")
            .path("totalBudget")
            .asInt());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM trip_versions WHERE trip_id=?", Integer.class, id));
  }

  @Test
  void simultaneousVersionWritesCannotOverwriteEachOther() throws Exception {
    String id = save(a, "杭州", 1, 1000).path("trip").path("id").asText();
    var one =
        a.client.sendAsync(
            a.request(
                "PUT",
                "/api/trips/" + id,
                JSON.writeValueAsString(Map.of("baseVersion", 1, "plan", plan("杭州", 1, 1200)))),
            HttpResponse.BodyHandlers.ofString());
    var two =
        a.client.sendAsync(
            a.request(
                "PUT",
                "/api/trips/" + id,
                JSON.writeValueAsString(Map.of("baseVersion", 1, "plan", plan("杭州", 1, 1500)))),
            HttpResponse.BodyHandlers.ofString());
    var statuses =
        new HashSet<>(
            List.of(
                one.get(5, TimeUnit.SECONDS).statusCode(),
                two.get(5, TimeUnit.SECONDS).statusCode()));
    assertEquals(Set.of(200, 409), statuses);
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM trip_versions WHERE trip_id=?", Integer.class, id));
  }

  @Test
  void otherUserCannotReadChangeDeleteOrAttachOwnedData() throws Exception {
    String trip = save(a, "杭州", 3, 2000).path("trip").path("id").asText(),
        conv = conversation(a, trip);
    var b = user();
    for (String path :
        List.of(
            "/api/trips/" + trip,
            "/api/trips/" + trip + "?version=1",
            "/api/conversations/" + conv,
            "/api/conversations/" + conv + "/messages"))
      assertEquals(404, b.send("GET", path, null).statusCode(), path);
    assertEquals(
        404,
        b.send(
                "PUT",
                "/api/trips/" + trip,
                JSON.writeValueAsString(Map.of("baseVersion", 1, "plan", plan("北京", 2, 1000))))
            .statusCode());
    assertEquals(404, b.send("DELETE", "/api/trips/" + trip, null).statusCode());
    assertEquals(404, b.send("DELETE", "/api/conversations/" + conv, null).statusCode());
    assertEquals(
        404,
        b.send("POST", "/api/conversations", JSON.writeValueAsString(Map.of("tripId", trip)))
            .statusCode());
    assertEquals(404, chat(b, conv, "侵入", UUID.randomUUID().toString()).statusCode());
    assertEquals(200, a.send("GET", "/api/trips/" + trip, null).statusCode());
  }

  @Test
  void multiTurnContextKeepsTripAndNewBudgetAndRestoresHistory() throws Exception {
    String trip = save(a, "杭州", 3, 2000).path("trip").path("id").asText(),
        conv = conversation(a, trip);
    assertTrue(
        chat(a, conv, "请记住我是亲子游", UUID.randomUUID().toString())
            .body()
            .contains("\"type\":\"done\""));
    String question = "预算改成1500元";
    var response = chat(a, conv, question, UUID.randomUUID().toString());
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("2000"));
    JsonNode outgoing = captured.get(question);
    String system = outgoing.path("messages").get(0).path("content").asText();
    assertTrue(system.contains("杭州"));
    assertTrue(system.contains("\"days\":3"));
    assertTrue(system.contains("2000"));
    assertTrue(outgoing.toString().contains("预算改成1500元"));
    assertEquals(
        List.of("system", "user", "assistant", "user"),
        java.util.stream.StreamSupport.stream(outgoing.path("messages").spliterator(), false)
            .map(node -> node.path("role").asText())
            .toList());
    assertTrue(outgoing.toString().contains("亲子游"));
    var restored = new TestApi(port);
    restored.login(a.username, TestApi.PASSWORD);
    var records = history(restored, conv).path("items");
    assertEquals(4, records.size());
    assertEquals("completed", records.get(3).path("status").asText());
    assertTrue(records.get(3).path("content").asText().contains("2000"));
    var context = restored.data("GET", "/api/conversations/" + conv, null).path("context");
    assertEquals("杭州", context.path("city").asText());
    assertEquals(3, context.path("days").asInt());
    assertEquals(2000, context.path("budget").asInt());
  }

  @Test
  void newConversationAndOtherUserNeverReceiveOldMessages() throws Exception {
    String first = conversation(a, null);
    chat(a, first, "我去杭州玩3天，预算2000元。秘密标记A", UUID.randomUUID().toString());
    String second = conversation(a, null);
    chat(a, second, "完全独立的新话题", UUID.randomUUID().toString());
    assertEquals(2, captured.get("完全独立的新话题").path("messages").size());
    assertFalse(captured.get("完全独立的新话题").toString().contains("秘密标记A"));
    var b = user();
    String third = conversation(b, null);
    chat(b, third, "用户B的话题", UUID.randomUUID().toString());
    assertFalse(captured.get("用户B的话题").toString().contains("秘密标记A"));
    assertFalse(captured.get("用户B的话题").toString().contains("杭州"));
  }

  @Test
  void failuresAndStopsArePersistedAndOtherUserCannotCancel() throws Exception {
    String conv = conversation(a, null),
        requestId = UUID.randomUUID().toString(),
        question = "slow-" + requestId;
    var response =
        a.client.send(
            a.request(
                "POST",
                "/api/travel/chat",
                JSON.writeValueAsString(
                    Map.of("requestId", requestId, "conversationId", conv, "message", question))),
            HttpResponse.BodyHandlers.ofInputStream());
    try (var reader =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      String line;
      do {
        line = reader.readLine();
      } while (line != null && !line.contains("\"type\":\"chunk\""));
      assertNotNull(line);
      var b = user();
      // B only reserves its own cancellation intent; A must remain active.
      assertTrue(
          b.data("DELETE", "/api/travel/chat/" + requestId, null).path("cancelled").asBoolean());
      assertEquals(
          requestId,
          a.data("GET", "/api/conversations/" + conv, null).path("activeRequestId").asText());
      a.data("DELETE", "/api/travel/chat/" + requestId, null);
    }
    var stopped = history(a, conv).path("items").get(1);
    assertEquals("stopped", stopped.path("status").asText());
    assertFalse(stopped.path("content").asText().isBlank());
    chat(a, conv, "fail-this-turn", UUID.randomUUID().toString());
    var failed = history(a, conv).path("items").get(3);
    assertEquals("error", failed.path("status").asText());
    assertEquals("MODEL_STREAM_INTERRUPTED", failed.path("errorCode").asText());
    chat(a, conv, "完成后继续", UUID.randomUUID().toString());
    assertEquals(2, captured.get("完成后继续").path("messages").size());
  }

  @Test
  void replayIsRejectedButSmallExplicitChatBudgetDoesNotBlockConversation() throws Exception {
    String conv = conversation(a, null), requestId = UUID.randomUUID().toString();
    chat(a, conv, "正常一轮", requestId);
    assertEquals(409, chat(a, conv, "重复请求", requestId).statusCode());
    assertEquals(2, history(a, conv).path("items").size());
    assertEquals(200, chat(a, conv, "预算改成50元", UUID.randomUUID().toString()).statusCode());
    assertEquals(4, history(a, conv).path("items").size());
    assertFalse(a.data("GET", "/api/conversations/" + conv, null).path("context").has("budget"));
    assertTrue(a.data("GET", "/api/conversations/" + conv, null).path("activeRequestId").isNull());
  }

  @Test
  void paginationDeletionAndForeignKeysPreserveRemainingHistory() throws Exception {
    String trip = save(a, "杭州", 1, 1000).path("trip").path("id").asText();
    save(a, "成都", 1, 1000);
    save(a, "北京", 1, 1000);
    var page = a.data("GET", "/api/trips?page=1&pageSize=2", null);
    assertEquals(3, page.path("total").asInt());
    assertEquals(2, page.path("items").size());
    assertEquals(1, a.data("GET", "/api/trips?page=2&pageSize=2", null).path("items").size());
    String conv = conversation(a, trip);
    for (int i = 0; i < 3; i++) chat(a, conv, "轮次" + i, UUID.randomUUID().toString());
    var latest = a.data("GET", "/api/conversations/" + conv + "/messages?pageSize=2", null);
    assertTrue(latest.path("hasMore").asBoolean());
    long cursor = latest.path("nextBefore").asLong();
    var older =
        a.data("GET", "/api/conversations/" + conv + "/messages?pageSize=2&before=" + cursor, null);
    assertTrue(older.path("items").get(1).path("seq").asLong() < cursor);
    a.data("DELETE", "/api/trips/" + trip, null);
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM trip_versions WHERE trip_id=?", Integer.class, trip));
    assertTrue(a.data("GET", "/api/conversations/" + conv, null).path("tripId").isNull());
    assertEquals(6, history(a, conv).path("items").size());
    a.data("DELETE", "/api/conversations/" + conv, null);
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM messages WHERE conversation_id=?", Integer.class, conv));
  }

  @Test
  void longHistoryIsBoundedWhileFullHistoryRemainsAndRestartRecoveryWorks() throws Exception {
    String conv = a.data("POST", "/api/conversations",
        "{\"city\":\"杭州\",\"days\":3,\"budget\":2000}").path("id").asText();
    for (int i = 0; i < 15; i++) {
      var turn =
          data.begin(
              a.userId,
              conv,
              UUID.randomUUID().toString(),
              i == 0 ? "我去杭州玩3天预算2000元" : "问题" + i + "x".repeat(1000));
      data.finish(turn, "答复" + i + "y".repeat(1000), "completed", null, null);
    }
    var next = data.begin(a.userId, conv, UUID.randomUUID().toString(), "预算改成1500元");
    assertTrue(next.messages().stream().mapToInt(m -> m.get("content").length()).sum() <= 12000);
    assertTrue(next.messages().get(0).get("content").contains("杭州"));
    assertTrue(next.messages().get(0).get("content").contains("2000"));
    assertEquals("预算改成1500元",next.messages().get(next.messages().size()-1).get("content"));
    assertTrue(next.messages().size() < 26);
    data.checkpoint(next, "已经保存的半段回复");
    data.recover();
    var records =
        a.data("GET", "/api/conversations/" + conv + "/messages?pageSize=100", null).path("items");
    assertEquals(32, records.size());
    var last = records.get(31);
    assertEquals("error", last.path("status").asText());
    assertEquals("SERVER_RESTARTED", last.path("errorCode").asText());
    assertEquals("已经保存的半段回复", last.path("content").asText());
    assertTrue(a.data("GET", "/api/conversations/" + conv, null).path("activeRequestId").isNull());
  }

  @Test
  void logoutStopsActiveRequestsAndSavedPartialReplyRemains() throws Exception {
    String conv = conversation(a, null), requestId = UUID.randomUUID().toString();
    var response =
        a.client.send(
            a.request(
                "POST",
                "/api/travel/chat",
                JSON.writeValueAsString(
                    Map.of(
                        "requestId", requestId, "conversationId", conv, "message", "slow-logout"))),
            HttpResponse.BodyHandlers.ofInputStream());
    try (var reader =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      String line;
      do {
        line = reader.readLine();
      } while (line != null && !line.contains("\"type\":\"chunk\""));
      assertNotNull(line);
      assertEquals(409, chat(a, conv, "同会话同时发送", UUID.randomUUID().toString()).statusCode());
      a.data("POST", "/api/auth/logout", "{}");
    }
    assertEquals(401, a.send("GET", "/api/conversations/" + conv, null).statusCode());
    a.login(a.username, TestApi.PASSWORD);
    assertEquals("stopped", history(a, conv).path("items").get(1).path("status").asText());
    assertTrue(a.data("GET", "/api/conversations/" + conv, null).path("activeRequestId").isNull());
  }

  @Test
  void deletingAnActiveOwnedConversationCancelsAndCascades() throws Exception {
    String conv = conversation(a, null), requestId = UUID.randomUUID().toString();
    var response =
        a.client.send(
            a.request(
                "POST",
                "/api/travel/chat",
                JSON.writeValueAsString(
                    Map.of(
                        "requestId", requestId, "conversationId", conv, "message", "slow-delete"))),
            HttpResponse.BodyHandlers.ofInputStream());
    try (var reader =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      String line;
      do {
        line = reader.readLine();
      } while (line != null && !line.contains("\"type\":\"chunk\""));
      assertNotNull(line);
      a.data("DELETE", "/api/conversations/" + conv, null);
    }
    assertEquals(404, a.send("GET", "/api/conversations/" + conv, null).statusCode());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM messages WHERE conversation_id=?", Integer.class, conv));
  }

  @Test
  void latestTripVersionRefreshesReferenceWithoutTextOverrides() throws Exception {
    String trip = save(a, "杭州", 3, 2000).path("trip").path("id").asText(),
        conv = conversation(a, trip);
    chat(a, conv, "预算改成1500元", UUID.randomUUID().toString());
    a.data(
        "PUT",
        "/api/trips/" + trip,
        JSON.writeValueAsString(Map.of("baseVersion", 1, "plan", plan("杭州", 4, 3000))));
    chat(a, conv, "继续安排新版本行程", UUID.randomUUID().toString());
    var state = a.data("GET", "/api/conversations/" + conv, null).path("context");
    assertEquals("杭州", state.path("city").asText());
    assertEquals(4, state.path("days").asInt());
    assertEquals(3000, state.path("budget").asInt());
    assertTrue(captured.get("继续安排新版本行程").toString().contains("第4天"));
  }

  @Test
  void mentioningTheSecondDayDoesNotChangeTripLengthAndPrecisionIsNotSilentlyLost()
      throws Exception {
    String trip = save(a, "杭州", 3, 2000).path("trip").path("id").asText(),
        conv = conversation(a, trip);
    var turn = data.begin(a.userId, conv, UUID.randomUUID().toString(), "第2天想去室内活动");
    data.finish(turn, "好的", "completed", null, null);
    assertEquals(
        3, a.data("GET", "/api/conversations/" + conv, null).path("context").path("days").asInt());
    assertEquals(200, chat(a, conv, "预算改成1500.123元", UUID.randomUUID().toString()).statusCode());
    assertEquals(4, history(a, conv).path("items").size());
    assertEquals(2000, a.data("GET", "/api/conversations/" + conv, null).path("context").path("budget").asInt());
    assertTrue(captured.get("预算改成1500.123元").toString().contains("1500.123"));
  }

  @Test
  void subBudgetQuestionsReachModelAndPersistWithoutChangingTripBudget() throws Exception {
    String trip = save(a, "成都", 3, 5000).path("trip").path("id").asText(),
        conv = conversation(a, trip);
    for (String message : List.of("预算50元在成都能吃什么？", "酒店预算300元一晚，有什么推荐？")) {
      var response = chat(a, conv, message, UUID.randomUUID().toString());
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"type\":\"done\""));
      assertTrue(captured.containsKey(message));
      var state = a.data("GET", "/api/conversations/" + conv, null).path("context");
      assertEquals(5000, state.path("budget").asInt());
      assertEquals(3, state.path("days").asInt());
    }
    assertEquals(4, history(a, conv).path("items").size());
    assertEquals(5000, a.data("GET", "/api/trips/" + trip, null).path("plan").path("totalBudget").asInt());
  }

  @Test
  void chatTextNeverRewritesTripReferenceAndRawRequirementsRemainInHistory() throws Exception {
    String trip = save(a, "成都", 3, 5000).path("trip").path("id").asText(),
        conv = conversation(a, trip);
    var original = a.data("GET", "/api/conversations/" + conv, null).path("context");
    for (String message : List.of("总预算1万元","把预算改为1.5万元","不去北京了","别安排玩5天")) {
      assertEquals(200, chat(a, conv, message, UUID.randomUUID().toString()).statusCode());
      assertEquals(original, a.data("GET", "/api/conversations/" + conv, null).path("context"));
      assertEquals(message, captured.get(message).path("messages")
          .get(captured.get(message).path("messages").size()-1).path("content").asText());
    }
    assertTrue(captured.get("别安排玩5天").toString().contains("总预算1万元"));
    assertEquals(8, history(a,conv).path("items").size());
    assertEquals(5000,a.data("GET","/api/trips/"+trip,null).path("plan").path("totalBudget").asInt());
  }

  @Test
  void malformedPlanCannotCreateOrAppendAVersion() throws Exception {
    var good = save(a,"杭州",3,2000);
    String id = good.path("trip").path("id").asText();
    for (String kind : List.of("missing-day","wrong-day","blank-spot","missing-slot")) {
      var malformed = plan("杭州",3,2000);
      var days = (com.fasterxml.jackson.databind.node.ArrayNode)malformed.path("dailyItinerary");
      if (kind.equals("missing-day")) days.remove(2);
      if (kind.equals("wrong-day")) ((com.fasterxml.jackson.databind.node.ObjectNode)days.get(0)).put("day",9);
      if (kind.equals("blank-spot")) ((com.fasterxml.jackson.databind.node.ObjectNode)days.get(0).path("morning")).put("spot"," ");
      if (kind.equals("missing-slot")) ((com.fasterxml.jackson.databind.node.ObjectNode)days.get(0)).remove("evening");
      assertEquals(400,a.send("POST","/api/trips",JSON.writeValueAsString(Map.of("plan",malformed))).statusCode());
      assertEquals(400,a.send("PUT","/api/trips/"+id,JSON.writeValueAsString(Map.of("baseVersion",1,"plan",malformed))).statusCode());
    }
    assertEquals(good,a.data("GET","/api/trips/"+id,null));
    assertEquals(1,a.data("GET","/api/trips",null).path("total").asInt());
  }

  @Test
  void earlyChatCancellationDoesNotCreateMessagesOrCallProvider() throws Exception {
    String conv=conversation(a,null),id=UUID.randomUUID().toString(),question="迟到-"+id;
    assertTrue(a.data("DELETE","/api/travel/chat/"+id,null).path("cancelled").asBoolean());
    var result=chat(a,conv,question,id);
    assertEquals(409,result.statusCode());
    assertEquals("CHAT_CANCELLED",JSON.readTree(result.body()).path("errorCode").asText());
    assertEquals(0,history(a,conv).path("items").size());
    assertFalse(captured.containsKey(question));
    assertTrue(a.data("GET","/api/conversations/"+conv,null).path("activeRequestId").isNull());
    assertEquals(200,chat(a,conv,"不同ID的新问题",UUID.randomUUID().toString()).statusCode());
  }

  @Test
  void longStoredReplyKeepsPreviousQuestionInNextContextWithoutChangingStoredAnswer() throws Exception {
    String conv=conversation(a,null),question="成都2天预算1200元";
    var first=data.begin(a.userId,conv,UUID.randomUUID().toString(),question);
    String full="walk ".repeat(2600);
    data.finish(first,full,"completed",null,null);
    var next=data.begin(a.userId,conv,UUID.randomUUID().toString(),"按前述要求继续");
    assertEquals(question,next.messages().get(1).get("content"));
    assertTrue(next.messages().get(2).get("content").endsWith("[历史回答已截短]"));
    assertTrue(next.messages().stream().mapToInt(m->m.get("content").length()).sum()<=12000);
    data.finish(next,"继续建议","completed",null,null);
    assertEquals(full,history(a,conv).path("items").get(1).path("content").asText());
    assertEquals(4,history(a,conv).path("items").size());
  }

  @Test
  void invalidPaginationAndIdentifiersAreRejectedWithoutChangingData() throws Exception {
    assertEquals(400, a.send("GET", "/api/trips?page=abc", null).statusCode());
    assertEquals(400, a.send("GET", "/api/trips?page=0", null).statusCode());
    assertEquals(400, a.send("GET", "/api/conversations?pageSize=1000", null).statusCode());
    String conv = conversation(a, null);
    assertEquals(
        400,
        a.send("GET", "/api/conversations/" + conv + "/messages?before=-1", null).statusCode());
  }

  @Test
  void savedTimestampsAreRealUtcInstantsInsteadOfEightHoursInTheFuture() throws Exception {
    java.time.Instant before = java.time.Instant.now().minusSeconds(2);
    var saved = save(a, "杭州", 1, 1000);
    java.time.Instant created =
        java.time.Instant.parse(saved.path("trip").path("createdAt").asText());
    assertFalse(created.isBefore(before));
    assertFalse(created.isAfter(java.time.Instant.now().plusSeconds(2)));
    String conv = conversation(a, saved.path("trip").path("id").asText());
    chat(a, conv, "验证消息时间", UUID.randomUUID().toString());
    for (var message : history(a, conv).path("items")) {
      java.time.Instant timestamp = java.time.Instant.parse(message.path("createdAt").asText());
      assertFalse(timestamp.isBefore(before));
      assertFalse(timestamp.isAfter(java.time.Instant.now().plusSeconds(2)));
    }
  }
}

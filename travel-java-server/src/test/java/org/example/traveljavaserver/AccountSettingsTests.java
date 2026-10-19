package org.example.traveljavaserver;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "knowledge.enabled=true")
class AccountSettingsTests {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String NEXT = "New-test-pass-2026";
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  TestApi a;

  @BeforeEach
  void setup() throws Exception {
    a = new TestApi(port);
    a.registerLogin();
  }

  String name() {
    return "rename_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  String body(Object value) throws Exception {
    return JSON.writeValueAsString(value);
  }

  Map<String, String> rename(String value, String password) {
    return Map.of("username", value, "currentPassword", password);
  }

  Map<String, String> password(String old, String next, String confirm) {
    return Map.of("currentPassword", old, "newPassword", next, "confirmPassword", confirm);
  }

  long version() {
    return jdbc.queryForObject(
        "SELECT credential_version FROM users WHERE id=?", Long.class, a.userId);
  }

  String hash() {
    return jdbc.queryForObject(
        "SELECT password_hash FROM users WHERE id=?", String.class, a.userId);
  }

  TestApi second() throws Exception {
    var b = new TestApi(port);
    b.login(a.username, TestApi.PASSWORD);
    return b;
  }

  record Fixtures(
      String trip,
      String conversation,
      String document,
      String question,
      JsonNode tripData,
      JsonNode messages,
      JsonNode documentData,
      JsonNode questionData) {}

  Fixtures fixtures() throws Exception {
    String trip = UUID.randomUUID().toString(),
        versionId = UUID.randomUUID().toString(),
        document = UUID.randomUUID().toString(),
        chunk = UUID.randomUUID().toString(),
        question = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO trips(id,user_id,title,city,days,budget) VALUES(?,?,'保留行程','杭州',1,500)",
        trip,
        a.userId);
    jdbc.update(
        "INSERT INTO trip_versions(id,trip_id,version,plan_json) VALUES(?,?,1,?)",
        versionId,
        trip,
        "{\"city\":\"杭州\",\"days\":1,\"totalBudget\":500,\"dailyItinerary\":[]}");
    String conversation = a.conversation();
    jdbc.update(
        "INSERT INTO messages(id,conversation_id,user_id,request_id,role,content,status)"
            + " VALUES(?,?,?,?,'assistant','保留聊天正文','completed')",
        UUID.randomUUID().toString(),
        conversation,
        a.userId,
        UUID.randomUUID().toString());
    String text = "这是一份文化学习测试资料，仅用于验证修改凭据后资料及引用保持不变。";
    jdbc.update(
        "INSERT INTO"
            + " knowledge_documents(id,user_id,title,city,source_url,origin,content,fingerprint,embedding_model,dimensions,chunk_count)"
            + " VALUES(?,?,'保留资料','杭州','','USER',?,?,'fixture-embedding',4,1)",
        document,
        a.userId,
        text,
        "a".repeat(64));
    jdbc.update(
        "INSERT INTO"
            + " knowledge_chunks(id,document_id,ordinal,start_offset,end_offset,content,embedding)"
            + " VALUES(?,?,1,0,?,?,?)",
        chunk,
        document,
        text.length(),
        text,
        "[1,0,0,0]");
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("ref", "C1");
    source.put("chunkId", chunk);
    source.put("documentId", document);
    source.put("ordinal", 1);
    source.put("title", "保留资料");
    source.put("city", "杭州");
    source.put("sourceUrl", "");
    source.put("origin", "USER");
    source.put("score", 1);
    source.put("content", text);
    source.put("importedAt", "2026-10-18T00:00:00Z");
    String answer =
        body(
            Map.of(
                "abstained",
                false,
                "claims",
                List.of(
                    Map.of(
                        "text",
                        "文化学习测试资料",
                        "citations",
                        List.of(Map.of("ref", "C1", "quote", "这是一份文化学习测试资料")))),
                "sources",
                List.of(source),
                "notice",
                "引用快照测试",
                "modelCalls",
                List.of()));
    jdbc.update(
        "INSERT INTO rag_questions(id,user_id,question,city,as_of,status,answer_json)"
            + " VALUES(?,?,'资料主题是什么','杭州','2026-10-18','READY',?)",
        question,
        a.userId,
        answer);
    return new Fixtures(
        trip,
        conversation,
        document,
        question,
        a.data("GET", "/api/trips/" + trip, null),
        a.data("GET", "/api/conversations/" + conversation + "/messages", null),
        a.data("GET", "/api/knowledge/documents/" + document, null),
        a.data("GET", "/api/knowledge/questions/" + question, null));
  }

  void intact(TestApi client, Fixtures f) throws Exception {
    assertEquals(a.userId, client.data("GET", "/api/auth/me", null).path("id").asLong());
    assertEquals(f.tripData, client.data("GET", "/api/trips/" + f.trip, null));
    assertEquals(
        f.messages, client.data("GET", "/api/conversations/" + f.conversation + "/messages", null));
    assertEquals(
        f.documentData, client.data("GET", "/api/knowledge/documents/" + f.document, null));
    assertEquals(
        f.questionData, client.data("GET", "/api/knowledge/questions/" + f.question, null));
    assertEquals(
        "[1, 0, 0, 0]",
        jdbc.queryForObject(
            "SELECT embedding FROM knowledge_chunks WHERE document_id=?",
            String.class,
            f.document));
  }

  @Test
  void existingShortCredentialsCanLoginAndBeUpgradedWithoutRelaxingRegistration() throws Exception {
    String original = a.username;
    String shortName = "z9", shortPassword = "Legacy";
    jdbc.update(
        "UPDATE users SET username=?,password_hash=? WHERE id=?",
        shortName,
        encoder.encode(shortPassword),
        a.userId);
    try {
      var client = new TestApi(port);
      client.login(shortName, shortPassword);
      assertEquals(a.userId, client.userId);
      client.data("PATCH", "/api/auth/password", body(password(shortPassword, NEXT, NEXT)));
      client.login(shortName, NEXT);
      client.data("PATCH", "/api/auth/username", body(rename(original, NEXT)));
      client.login(original, NEXT);
      assertEquals(a.userId, client.userId);
      var fresh = new TestApi(port);
      fresh.csrf();
      assertEquals(
          400,
          fresh
              .send(
                  "POST",
                  "/api/auth/register",
                  body(Map.of("username", shortName, "password", NEXT)))
              .statusCode());
      assertEquals(
          400,
          fresh
              .send(
                  "POST",
                  "/api/auth/register",
                  body(Map.of("username", name(), "password", shortPassword)))
              .statusCode());
    } finally {
      jdbc.update("UPDATE users SET username=? WHERE id=?", original, a.userId);
    }
  }

  @Test
  void renameKeepsIdentityAllDataAndRevokesOldSessions() throws Exception {
    var f = fixtures();
    var b = second();
    String old = a.username, next = name(), hash = hash();
    var other = new TestApi(port);
    other.registerLogin();
    a.data(
        "PATCH",
        "/api/auth/username",
        body(rename(next.toUpperCase(Locale.ROOT), TestApi.PASSWORD)));
    assertEquals(1, version());
    assertEquals(hash, hash());
    assertEquals(401, a.send("GET", "/api/auth/me", null).statusCode());
    assertEquals(401, b.send("GET", "/api/auth/me", null).statusCode());
    assertEquals(200, other.send("GET", "/api/auth/me", null).statusCode());
    assertEquals(
        404, other.send("GET", "/api/knowledge/documents/" + f.document, null).statusCode());
    var login = new TestApi(port);
    login.csrf();
    assertEquals(
        401,
        login
            .send(
                "POST",
                "/api/auth/login",
                body(Map.of("username", old, "password", TestApi.PASSWORD)))
            .statusCode());
    login.login(next, TestApi.PASSWORD);
    intact(login, f);
  }

  @Test
  void passwordKeepsAllDataRehashesAndRevokesOldCredentials() throws Exception {
    var f = fixtures();
    var b = second();
    String oldHash = hash(), username = a.username;
    a.data("PATCH", "/api/auth/password", body(password(TestApi.PASSWORD, NEXT, NEXT)));
    assertEquals(1, version());
    assertNotEquals(oldHash, hash());
    assertTrue(encoder.matches(NEXT, hash()));
    assertFalse(encoder.matches(TestApi.PASSWORD, hash()));
    assertEquals(401, a.send("GET", "/api/auth/me", null).statusCode());
    assertEquals(401, b.send("GET", "/api/auth/me", null).statusCode());
    var login = new TestApi(port);
    login.csrf();
    assertEquals(
        401,
        login
            .send(
                "POST",
                "/api/auth/login",
                body(Map.of("username", username, "password", TestApi.PASSWORD)))
            .statusCode());
    login.login(username, NEXT);
    intact(login, f);
  }

  @Test
  void wrongCurrentPasswordDoesNotUpdateOrSignOut() throws Exception {
    String oldHash = hash();
    for (String endpoint : List.of("username", "password")) {
      var r =
          a.send(
              "PATCH",
              "/api/auth/" + endpoint,
              body(
                  endpoint.equals("username")
                      ? rename(name(), "Wrong-password")
                      : password("Wrong-password", NEXT, NEXT)));
      assertEquals(400, r.statusCode());
      assertEquals("CURRENT_PASSWORD_INVALID", JSON.readTree(r.body()).path("errorCode").asText());
    }
    assertEquals(0, version());
    assertEquals(oldHash, hash());
    assertEquals(a.username, a.data("GET", "/api/auth/me", null).path("username").asText());
  }

  @Test
  void duplicateOrUnchangedUsernameLeavesAccountUsable() throws Exception {
    var b = new TestApi(port);
    b.registerLogin();
    var duplicate =
        a.send(
            "PATCH",
            "/api/auth/username",
            body(rename(b.username.toUpperCase(Locale.ROOT), TestApi.PASSWORD)));
    assertEquals(409, duplicate.statusCode());
    assertEquals("USERNAME_TAKEN", JSON.readTree(duplicate.body()).path("errorCode").asText());
    assertEquals(
        400,
        a.send(
                "PATCH",
                "/api/auth/username",
                body(rename(a.username.toUpperCase(Locale.ROOT), TestApi.PASSWORD)))
            .statusCode());
    assertEquals(0, version());
    assertEquals(200, a.send("GET", "/api/auth/me", null).statusCode());
  }

  @Test
  void malformedNamesAndPasswordsCannotMutateAccount() throws Exception {
    assertEquals(
        400,
        a.send("PATCH", "/api/auth/username", body(rename("非法名字", TestApi.PASSWORD))).statusCode());
    for (var request :
        List.of(
            password(TestApi.PASSWORD, NEXT, "Mismatch-pass"),
            password(TestApi.PASSWORD, TestApi.PASSWORD, TestApi.PASSWORD),
            password(TestApi.PASSWORD, "short", "short"),
            password(TestApi.PASSWORD, "密".repeat(25), "密".repeat(25)),
            password("密".repeat(25), NEXT, NEXT))) {
      assertEquals(400, a.send("PATCH", "/api/auth/password", body(request)).statusCode());
    }
    assertEquals(0, version());
    assertEquals(200, a.send("GET", "/api/auth/me", null).statusCode());
  }

  @Test
  void credentialChangesRequireLoginAndCsrf() throws Exception {
    var anonymous = new TestApi(port);
    anonymous.csrf();
    for (String endpoint : List.of("username", "password")) {
      String request =
          body(
              endpoint.equals("username")
                  ? rename(name(), TestApi.PASSWORD)
                  : password(TestApi.PASSWORD, NEXT, NEXT));
      assertEquals(401, anonymous.send("PATCH", "/api/auth/" + endpoint, request).statusCode());
      assertEquals(
          403,
          a.client
              .send(
                  a.request("PATCH", "/api/auth/" + endpoint, request, false),
                  java.net.http.HttpResponse.BodyHandlers.ofString())
              .statusCode());
    }
    assertEquals(0, version());
  }

  @Test
  void concurrentEditsCannotReuseStaleAuthentication() throws Exception {
    var b = second();
    String n1 = name(), n2 = name();
    var pool = Executors.newFixedThreadPool(2);
    try {
      var start = new CountDownLatch(1);
      var one =
          pool.submit(
              () -> {
                start.await();
                return a.send("PATCH", "/api/auth/username", body(rename(n1, TestApi.PASSWORD)))
                    .statusCode();
              });
      var two =
          pool.submit(
              () -> {
                start.await();
                return b.send("PATCH", "/api/auth/username", body(rename(n2, TestApi.PASSWORD)))
                    .statusCode();
              });
      start.countDown();
      var statuses =
          new ArrayList<>(List.of(one.get(8, TimeUnit.SECONDS), two.get(8, TimeUnit.SECONDS)));
      Collections.sort(statuses);
      assertEquals(List.of(200, 401), statuses);
      assertEquals(1, version());
    } finally {
      pool.shutdownNow();
    }
  }
}

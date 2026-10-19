package org.example.traveljavaserver.planning;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.traveljavaserver.common.AppException;
import org.junit.jupiter.api.*;

class TravelToolsFallbackTests {
  static final String PLACES =
      "{\"elements\":[{\"type\":\"node\",\"id\":1,\"lat\":30.25,\"lon\":120.15,\"tags\":{\"name\":\"景点甲\",\"tourism\":\"museum\"}},{\"type\":\"node\",\"id\":2,\"lat\":30.251,\"lon\":120.151,\"tags\":{\"name\":\"景点乙\",\"tourism\":\"museum\"}},{\"type\":\"node\",\"id\":3,\"lat\":30.252,\"lon\":120.152,\"tags\":{\"name\":\"景点丙\",\"tourism\":\"attraction\"}}]}";
  Work work;

  @BeforeEach
  void scope() {
    work =
        new Work(
            1, new PlanTypes.Start("杭州", 1, new java.math.BigDecimal("1000")), null, null, 10000);
    Work.CURRENT.set(work);
  }

  @AfterEach
  void clear() {
    Work.CURRENT.remove();
  }

  static class Provider implements AutoCloseable {
    final HttpServer server;
    final AtomicInteger primary = new AtomicInteger(), backup = new AtomicInteger();
    final TravelTools tools;

    Provider(int first, int second, Runnable onFirst) throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/primary",
          e -> {
            primary.incrementAndGet();
            onFirst.run();
            reply(e, first);
          });
      server.createContext(
          "/backup",
          e -> {
            backup.incrementAndGet();
            reply(e, second);
          });
      server.start();
      String root = "http://127.0.0.1:" + server.getAddress().getPort();
      tools = new TravelTools(root + "/primary," + root + "/backup", root, "");
    }

    static void reply(HttpExchange e, int code) throws java.io.IOException {
      byte[] data =
          (code == 200 ? PLACES : "{\"error\":\"unavailable\"}").getBytes(StandardCharsets.UTF_8);
      e.getResponseHeaders().set("Content-Type", "application/json");
      e.sendResponseHeaders(code, data.length);
      try (var output = e.getResponseBody()) {
        output.write(data);
      } finally {
        e.close();
      }
    }

    public void close() {
      tools.close();
      server.stop(0);
    }
  }

  @Test
  void serverFailureSwitchesOnceToBackupAndRecordsBothSources() throws Exception {
    try (var p = new Provider(504, 200, () -> {})) {
      var result = p.tools.search("杭州");
      assertEquals(3, result.items().size());
      assertFalse(result.cached());
      assertEquals(1, p.primary.get());
      assertEquals(1, p.backup.get());
      assertEquals(2, work.used);
      assertEquals("error", work.traces.get(0).status());
      assertEquals("ok", work.traces.get(1).status());
      assertTrue(work.traces.get(0).detail().contains("504"));
    }
  }

  @Test
  void healthyPrimaryAvoidsBackupAndSubsequentSearchUsesFreshCache() throws Exception {
    try (var p = new Provider(200, 503, () -> {})) {
      var one = p.tools.search("杭州");
      var two = p.tools.search("杭州");
      assertTrue(two.cached());
      assertEquals(one.queriedAt(), two.queriedAt());
      assertEquals(1, p.primary.get());
      assertEquals(0, p.backup.get());
      assertEquals(1, work.used);
    }
  }

  @Test
  void rateLimitAndOtherClientErrorsDoNotTriggerAnotherProvider() throws Exception {
    for (int status : new int[] {429, 406, 400})
      try (var p = new Provider(status, 200, () -> {})) {
        var error = assertThrows(AppException.class, () -> p.tools.search("杭州"));
        assertEquals("TOOL_HTTP_ERROR", error.getErrorCode());
        assertEquals(1, p.primary.get());
        assertEquals(0, p.backup.get());
      }
  }

  @Test
  void allSourcesFailWithoutSynthesizingDataOrRetryingForever() throws Exception {
    try (var p = new Provider(503, 504, () -> {})) {
      var error = assertThrows(AppException.class, () -> p.tools.search("杭州"));
      assertEquals("TOOL_HTTP_ERROR", error.getErrorCode());
      assertTrue(error.getMessage().contains("2个公开节点"));
      assertEquals(1, p.primary.get());
      assertEquals(1, p.backup.get());
    }
  }

  @Test
  void cancellationPreventsBackupRequest() throws Exception {
    try (var p = new Provider(503, 200, () -> work.cancelled.set(true))) {
      assertThrows(java.util.concurrent.CancellationException.class, () -> p.tools.search("杭州"));
      assertEquals(0, p.backup.get());
    }
  }
}

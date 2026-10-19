package org.example.traveljavaserver;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.example.traveljavaserver.utils.LLMutils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BaselineHttpTests {
  @LocalServerPort private int port;
  private TestApi api;

  @org.junit.jupiter.api.BeforeEach
  void login() throws Exception {
    api = new TestApi(port);
    api.registerLogin();
  }

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void helloWorksOverHttp() throws Exception {
    var response =
        api.client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/travel/hello"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    var body = mapper.readTree(response.body());
    assertTrue(body.path("success").asBoolean());
    assertEquals("hello world", body.path("data").asText());
  }

  @Test
  void invalidTripIsRejectedBeforeCallingTheModel() throws Exception {
    var request =
        api.request("POST", "/api/travel/recommend", "{\"city\":null,\"days\":0,\"budget\":50}");
    var response = api.client.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(400, response.statusCode());
    var body = mapper.readTree(response.body());
    assertFalse(body.path("success").asBoolean());
    assertEquals(400, body.path("code").asInt());
    assertFalse(body.path("message").asText().isBlank());
  }

  @Test
  void missingKeyFailsLocallyWithoutSendingARequest() {
    var llm = new LLMutils("", "http://127.0.0.1:1", "test-model");
    var error =
        assertThrows(
            org.example.traveljavaserver.common.AppException.class, () -> llm.chat(null, "test"));
    assertTrue(error.getMessage().contains("LLM_API_KEY"));
  }
}

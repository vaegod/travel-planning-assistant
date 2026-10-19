package org.example.traveljavaserver;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

public class TestApi {
  public static final ObjectMapper JSON = new ObjectMapper();
  public final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
  public final HttpClient client =
      HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(3)).build();
  public final int port;
  public String csrfHeader = "X-CSRF-TOKEN", csrfToken, username;
  public long userId;
  public static final String PASSWORD = "Test-only-pass-2026";

  public TestApi(int port) {
    this.port = port;
  }

  public HttpRequest request(String method, String path, String body) {
    return request(method, path, body, true);
  }

  public HttpRequest request(String method, String path, String body, boolean csrf) {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(8));
    if (csrf && csrfToken != null) builder.header(csrfHeader, csrfToken);
    if (body != null) builder.header("Content-Type", "application/json");
    return builder
        .method(
            method,
            body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body))
        .build();
  }

  public HttpResponse<String> send(String method, String path, String body) throws Exception {
    return client.send(request(method, path, body), HttpResponse.BodyHandlers.ofString());
  }

  public JsonNode data(String method, String path, String body) throws Exception {
    var response = send(method, path, body);
    if (response.statusCode() != 200)
      throw new AssertionError(
          path + " status=" + response.statusCode() + " body=" + response.body());
    return JSON.readTree(response.body()).path("data");
  }

  public void csrf() throws Exception {
    var value = data("GET", "/api/auth/csrf", null);
    csrfHeader = value.path("headerName").asText();
    csrfToken = value.path("token").asText();
  }

  public void registerLogin() throws Exception {
    username = "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    csrf();
    String body = JSON.writeValueAsString(Map.of("username", username, "password", PASSWORD));
    data("POST", "/api/auth/register", body);
    login(username, PASSWORD);
  }

  public void login(String name, String password) throws Exception {
    csrf();
    var user =
        data(
            "POST",
            "/api/auth/login",
            JSON.writeValueAsString(Map.of("username", name, "password", password)));
    userId = user.path("id").asLong();
    username = user.path("username").asText();
    csrf();
  }

  public String conversation() throws Exception {
    return data("POST", "/api/conversations", "{}").path("id").asText();
  }

  public String cookieHeader() {
    return cookies.getCookieStore().getCookies().stream()
        .map(c -> c.getName() + "=" + c.getValue())
        .collect(java.util.stream.Collectors.joining("; "));
  }
}

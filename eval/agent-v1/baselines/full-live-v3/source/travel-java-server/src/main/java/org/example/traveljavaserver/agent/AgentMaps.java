package org.example.traveljavaserver.agent;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Optional Amap adapter. No invented data when a key is missing or an upstream query fails. */
@Component
class AgentMaps {
  record Place(String id, String name, String address, double latitude, double longitude, String queriedAt) {}
  record Route(String fromId, String toId, int minutes, double meters, double seconds,
      String profile, String summary, BigDecimal estimatedFare, String fareNotice, String queriedAt) {}
  private final String key, base;
  private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
  AgentMaps(@Value("${agent.amap-key:}") String key,
      @Value("${agent.amap-base-url:https://restapi.amap.com}") String base) {
    this.key = key; this.base = base.replaceAll("/+$", "");
  }
  boolean configured() { return !key.isBlank(); }
  List<Place> search(AgentRun run, String keywords) {
    if (!configured()) throw new IllegalArgumentException("未配置地图服务，可继续生成参考草稿，但不得声称已查询真实地点或路线");
    if (keywords == null || keywords.isBlank() || keywords.length() > 80) throw new IllegalArgumentException("景点关键词须为1～80字");
    var data = fetch(run, "/v3/place/text", Map.of("city", run.start.city(), "citylimit", "true",
        "keywords", keywords, "offset", "10", "page", "1", "extensions", "base"));
    var places = new ArrayList<Place>();
    for (var p : data.path("pois")) {
      String[] xy = p.path("location").asText().split(",");
      if (xy.length != 2 || p.path("id").asText().isBlank() || p.path("name").asText().isBlank()) continue;
      try {
        double lon = Double.parseDouble(xy[0]), lat = Double.parseDouble(xy[1]);
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) continue;
        places.add(new Place(p.path("id").asText(), p.path("name").asText(), p.path("address").asText(),
            lat, lon, Instant.now().toString()));
      } catch (NumberFormatException ignored) {}
      if (places.size() >= 10) break;
    }
    return List.copyOf(places);
  }
  Route route(AgentRun run, Place from, Place to) {
    String mode = run.start.travelMode();
    String label = TravelModes.label(mode);
    var parameters = new LinkedHashMap<String, String>();
    parameters.put("origin", coordinate(from));
    parameters.put("destination", coordinate(to));
    String path = switch (mode) {
      case "walking" -> "/v3/direction/walking";
      case "driving" -> "/v3/direction/driving";
      case "transit" -> "/v3/direction/transit/integrated";
      default -> throw new IllegalArgumentException("不支持的出行方式");
    };
    if (mode.equals("transit")) {
      parameters.put("city", run.start.city());
      parameters.put("cityd", run.start.city());
      parameters.put("strategy", "0");
      parameters.put("extensions", "base");
    } else if (mode.equals("driving")) {
      parameters.put("strategy", "0");
      parameters.put("originid", from.id());
      parameters.put("destinationid", to.id());
      parameters.put("extensions", "base");
    }
    var root = fetch(run, path, parameters).path("route");
    var choices = root.path(mode.equals("transit") ? "transits" : "paths");
    for (var p : choices) {
      if (mode.equals("transit") && !hasPublicTransport(p)) continue;
      double seconds = number(p.path("duration"));
      double meters = mode.equals("transit") ? transitDistance(p) : number(p.path("distance"));
      if (!Double.isFinite(seconds) || !Double.isFinite(meters) || seconds < 0 || seconds > 864000
          || meters < 0 || meters > 5000000) continue;
      BigDecimal fare = mode.equals("walking") ? BigDecimal.ZERO
          : mode.equals("transit") ? money(p.path("cost")) : null;
      String summary = mode.equals("transit") ? transitSummary(p) : "";
      String notice = mode.equals("transit") ? "公共交通参考票价；实际票价及班次请以运营方为准"
          : mode.equals("driving") ? "未计入油费、停车费等完整自驾成本" : "不计步行票价";
      if (mode.equals("driving")) {
        BigDecimal tolls = money(p.path("tolls"));
        if (tolls != null) notice = "收费路段通行费参考" + tolls.toPlainString() + "元；" + notice;
      }
      return new Route(from.id(), to.id(), (int) Math.ceil(seconds / 60), meters, seconds,
          mode, summary, fare, notice, Instant.now().toString());
    }
    throw new IllegalArgumentException("地图未返回可用" + label + "方案，请调整地点或出行方式");
  }
  private static String coordinate(Place place) {
    return String.format(Locale.ROOT, "%.6f,%.6f", place.longitude(), place.latitude());
  }
  private static double number(JsonNode value) {
    if (!value.isNumber() && !value.isTextual()) return Double.NaN;
    try { return Double.parseDouble(value.asText()); } catch (NumberFormatException e) { return Double.NaN; }
  }
  private static BigDecimal money(JsonNode value) {
    if (!value.isNumber() && !value.isTextual()) return null;
    try {
      var amount = new BigDecimal(value.asText());
      return AgentRules.money(amount) ? amount : null;
    } catch (NumberFormatException e) { return null; }
  }
  private static double transitDistance(JsonNode transit) {
    double total = number(transit.path("distance"));
    if (Double.isFinite(total) && total >= 0) return total;
    // The enclosing route.distance is the walking distance, not this transit itinerary.
    // Sum only the selected line in each segment (other buslines are alternatives).
    var segments = transit.path("segments");
    if (!segments.isArray() || segments.isEmpty()) return Double.NaN;
    total = 0;
    for (var segment : segments) {
      boolean found = false;
      for (JsonNode part : List.of(segment.path("walking"), segment.path("bus").path("buslines").path(0),
          segment.path("railway"), segment.path("taxi"))) {
        if (!part.isObject() || part.isEmpty()) continue;
        double distance = number(part.path("distance"));
        if (!Double.isFinite(distance) || distance < 0) return Double.NaN;
        total += distance;
        found = true;
      }
      if (!found) return Double.NaN;
    }
    return total;
  }
  private static boolean hasPublicTransport(JsonNode transit) {
    for (var segment : transit.path("segments")) {
      var line = segment.path("bus").path("buslines").path(0);
      var railway = segment.path("railway");
      if (!line.path("name").asText("").isBlank() || !line.path("id").asText("").isBlank()
          || !railway.path("name").asText("").isBlank() || !railway.path("id").asText("").isBlank()) return true;
    }
    return false;
  }
  private static String transitSummary(JsonNode transit) {
    var parts = new ArrayList<String>();
    for (var segment : transit.path("segments")) {
      double walk = number(segment.path("walking").path("distance"));
      if (Double.isFinite(walk) && walk > 0) parts.add("步行" + Math.round(walk) + "米");
      var line = segment.path("bus").path("buslines").path(0);
      if (line.isObject()) {
        String name = line.path("name").asText("");
        String from = line.path("departure_stop").path("name").asText("");
        String to = line.path("arrival_stop").path("name").asText("");
        if (!name.isBlank()) parts.add(name + (from.isBlank() || to.isBlank() ? "" : "（" + from + "→" + to + "）"));
      }
      var railway = segment.path("railway").path("name").asText("");
      if (!railway.isBlank()) parts.add(railway);
    }
    String summary = String.join(" → ", parts);
    return summary.length() > 260 ? summary.substring(0, 260) + "…" : summary;
  }
  private JsonNode fetch(AgentRun run, String path, Map<String, String> args) {
    var builder = Objects.requireNonNull(HttpUrl.parse(base + path)).newBuilder().addQueryParameter("key", key);
    args.forEach(builder::addQueryParameter);
    var request = new Request.Builder().url(builder.build());
    prepareRequest(run, request);
    var call = client.newCall(request.build());
    call.timeout().timeout(Math.min(10000, run.remaining()), TimeUnit.MILLISECONDS);
    run.track(call);
    try (var response = call.execute()) {
      if (!response.isSuccessful() || response.body() == null) throw new IllegalArgumentException("地图服务暂不可用");
      byte[] bytes = response.body().byteStream().readNBytes(262145);
      if (bytes.length > 262144) throw new IllegalArgumentException("地图响应过长");
      var root = AgentRules.JSON.readTree(bytes);
      if (root == null || !"1".equals(root.path("status").asText()))
        throw new IllegalArgumentException("地图查询失败，请检查服务配置与配额");
      return root;
    } catch (java.io.IOException e) {
      run.remaining();
      throw new IllegalArgumentException("地图请求未完成，不能提供已查询结果");
    } finally { run.calls.remove(call); }
  }
  /** Extension for isolated fixture adapters; normal requests add no evaluation headers. */
  protected void prepareRequest(AgentRun run, Request.Builder request) {}
  @PreDestroy void close() { client.dispatcher().executorService().shutdownNow(); client.connectionPool().evictAll(); }
}


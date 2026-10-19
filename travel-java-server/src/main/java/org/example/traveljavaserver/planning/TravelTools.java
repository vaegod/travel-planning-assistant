package org.example.traveljavaserver.planning;

import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import java.math.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "planning.external-enabled",
    havingValue = "true")
public class TravelTools {
  private final ObjectMapper json = new ObjectMapper();
  private final OkHttpClient http =
      new OkHttpClient.Builder()
          .connectTimeout(8, TimeUnit.SECONDS)
          .readTimeout(18, TimeUnit.SECONDS)
          .callTimeout(22, TimeUnit.SECONDS)
          .retryOnConnectionFailure(false)
          .build();
  private final List<String> overpass;
  private final String osrm;
  private final Path cacheDir;
  private final long cacheTtlSeconds;
  private final Map<String, PlanTypes.Places> cache =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final Map<String, java.util.concurrent.locks.ReentrantLock> locks =
      new java.util.concurrent.ConcurrentHashMap<>();

  @org.springframework.beans.factory.annotation.Autowired
  public TravelTools(
      @Value(
              "${planning.overpass-url:https://overpass-api.de/api/interpreter,https://maps.mail.ru/osm/tools/overpass/api/interpreter}")
          String overpass,
      @Value("${planning.osrm-url:https://router.project-osrm.org}") String osrm,
      @Value("${planning.cache-dir:}") String cacheDir,
      @Value("${planning.cache-ttl-seconds:3600}") long cacheTtlSeconds) {
    if (cacheTtlSeconds < 0 || cacheTtlSeconds > 86400)
      throw new IllegalArgumentException("Invalid planning cache TTL");
    this.cacheTtlSeconds = cacheTtlSeconds;
    this.overpass =
        Arrays.stream(overpass.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .toList();
    if (this.overpass.isEmpty()
        || this.overpass.size() > 2
        || this.overpass.stream().anyMatch(s -> HttpUrl.parse(s) == null))
      throw new IllegalArgumentException("Configure one or two valid Overpass URLs");
    this.osrm = osrm.replaceAll("/+$", "");
    this.cacheDir = cacheDir.isBlank() ? null : Path.of(cacheDir);
  }

  public TravelTools(String overpass, String osrm, String cacheDir) {
    this(overpass, osrm, cacheDir, 3600);
  }

  public static String bbox(String city) {
    return switch (city) {
      case "杭州" -> "30.245,120.145,30.265,120.165";
      case "成都" -> "30.655,104.045,30.675,104.065";
      default ->
          throw new org.example.traveljavaserver.common.AppException(
              org.springframework.http.HttpStatus.BAD_REQUEST, "UNSUPPORTED_CITY", "当前真实查询支持杭州、成都");
    };
  }

  @Tool(name = "search_places", description = "查询支持城市内的OpenStreetMap旅游景点及文化场所，返回带来源与时间的候选地点。")
  public PlanTypes.Places search(@ToolParam(description = "杭州或成都") String city) {
    Work work = Work.current();
    work.remaining();
    bbox(city);
    PlanTypes.Places existing = cache.computeIfAbsent(city, this::readCache);
    if (fresh(existing))
      return new PlanTypes.Places(
          city, existing.items(), existing.source(), existing.queriedAt(), true);
    var lock = locks.computeIfAbsent(city, key -> new java.util.concurrent.locks.ReentrantLock());
    try {
      if (!lock.tryLock(Math.min(work.remaining(), 22000), TimeUnit.MILLISECONDS))
        throw Work.error("TOOL_BUSY", "景点查询正在处理中，请稍后重试");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new java.util.concurrent.CancellationException();
    }
    try {
      existing = cache.get(city);
      if (fresh(existing))
        return new PlanTypes.Places(
            city, existing.items(), existing.source(), existing.queriedAt(), true);
      String bounds = bbox(city),
          query = "[out:json][timeout:10];nwr[\"tourism\"](" + bounds + ");out center 80;";
      JsonNode result = fetchPlaces(query);
      if (result.has("remark")) throw Work.error("PLACE_QUERY_FAILED", "景点服务未完整返回结果，请稍后重试");
      String at = Instant.now().toString();
      List<PlanTypes.Place> items = new ArrayList<>();
      Set<String> names = new HashSet<>();
      for (JsonNode item : result.path("elements")) {
        var tags = item.path("tags");
        String name = tags.path("name:zh").asText(tags.path("name").asText("")).trim();
        if (name.isEmpty() || name.length() > 100 || names.contains(name)) continue;
        JsonNode center = item.has("lat") ? item : item.path("center");
        if (!center.path("lat").isNumber() || !center.path("lon").isNumber()) continue;
        String type = item.path("type").asText();
        long id = item.path("id").asLong();
        if (!Set.of("node", "way", "relation").contains(type) || id <= 0) continue;
        String category =
            tags.path("tourism")
                .asText(tags.path("amenity").asText(tags.path("shop").asText("attraction")));
        if (!Set.of(
                "museum", "attraction", "viewpoint", "gallery", "theatre", "arts_centre", "mall")
            .contains(category)) continue;
        boolean indoor =
            !tags.path("indoor").asText().equals("no")
                && (tags.path("indoor").asText().equals("yes")
                    || Set.of("museum", "gallery", "theatre", "arts_centre", "mall")
                        .contains(category));
        double latitude = center.path("lat").asDouble(), longitude = center.path("lon").asDouble();
        if (!Double.isFinite(latitude)
            || !Double.isFinite(longitude)
            || Math.abs(latitude) > 90
            || Math.abs(longitude) > 180) continue;
        names.add(name);
        items.add(
            new PlanTypes.Place(
                type + "/" + id,
                name,
                latitude,
                longitude,
                indoor,
                category,
                "https://www.openstreetmap.org/" + type + "/" + id,
                at));
      }
      if (items.size() < 3) throw Work.error("NO_PLACES", "景点服务返回的可用地点不足，未生成替代数据");
      PlanTypes.Places found =
          new PlanTypes.Places(city, List.copyOf(items), "OpenStreetMap / Overpass", at, false);
      cache.put(city, found);
      writeCache(found);
      return found;
    } finally {
      lock.unlock();
    }
  }

  @Tool(name = "route_places", description = "根据本次景点查询返回的地点ID，查询OSRM驾车路线距离和时长。仅为路网估算，非实时交通或报价。")
  public PlanTypes.Routes route(
      @ToolParam(description = "杭州或成都") String city,
      @ToolParam(description = "按访问顺序排列的2至9个地点ID") List<String> placeIds) {
    if (placeIds == null || placeIds.size() < 2 || placeIds.size() > 9)
      throw Work.error("INVALID_TOOL_ARGUMENT", "路线需要2至9个地点");
    PlanTypes.Places places = cache.get(city);
    if (places == null) throw Work.error("INVALID_TOOL_ARGUMENT", "必须先查询同一城市的景点");
    Map<String, PlanTypes.Place> byId = new HashMap<>();
    places.items().forEach(place -> byId.put(place.id(), place));
    List<PlanTypes.Place> points = new ArrayList<>();
    for (String id : placeIds) {
      var place = byId.get(id);
      if (place == null) throw Work.error("INVALID_TOOL_ARGUMENT", "路线包含未查询到的景点ID");
      points.add(place);
    }
    StringJoiner coordinates = new StringJoiner(";");
    points.forEach(point -> coordinates.add(point.longitude() + "," + point.latitude()));
    String radiuses = String.join(";", Collections.nCopies(points.size(), "1000"));
    HttpUrl url =
        Objects.requireNonNull(HttpUrl.parse(osrm + "/route/v1/driving/" + coordinates))
            .newBuilder()
            .addQueryParameter("overview", "false")
            .addQueryParameter("steps", "false")
            .addQueryParameter("radiuses", radiuses)
            .build();
    JsonNode result =
        fetch(
            new Request.Builder()
                .url(url)
                .header("User-Agent", "TravelStudyAssistant/0.3")
                .build());
    if (!result.path("code").asText().equals("Ok"))
      throw Work.error("NO_ROUTE", "路线服务没有找到可用路径，未使用直线距离代替");
    JsonNode legs = result.path("routes").path(0).path("legs");
    if (!legs.isArray() || legs.size() != points.size() - 1)
      throw Work.error("INVALID_ROUTE_RESPONSE", "路线服务返回的数据不完整");
    String at = Instant.now().toString();
    List<PlanTypes.Leg> output = new ArrayList<>();
    for (int i = 0; i < legs.size(); i++) {
      JsonNode leg = legs.get(i);
      double meters = leg.path("distance").asDouble(-1),
          seconds = leg.path("duration").asDouble(-1);
      if (!Double.isFinite(meters) || !Double.isFinite(seconds) || meters < 0 || seconds < 0)
        throw Work.error("INVALID_ROUTE_RESPONSE", "路线距离或时长不正确");
      BigDecimal fare =
          BigDecimal.valueOf(10 + meters / 1000 * 2).setScale(2, RoundingMode.HALF_UP);
      output.add(
          new PlanTypes.Leg(
              placeIds.get(i),
              placeIds.get(i + 1),
              meters,
              seconds,
              "driving",
              "OSRM / OpenStreetMap",
              at,
              fare));
    }
    return new PlanTypes.Routes(List.copyOf(output), "OSRM / OpenStreetMap", at);
  }

  private JsonNode fetch(Request request) {
    return fetch(request, 22000);
  }

  private static final class RetryableSourceFailure
      extends org.example.traveljavaserver.common.AppException {
    RetryableSourceFailure(String message) {
      super(org.springframework.http.HttpStatus.BAD_GATEWAY, "TOOL_HTTP_ERROR", message);
    }
  }

  private JsonNode fetchPlaces(String query) {
    Work work = Work.current();
    org.example.traveljavaserver.common.AppException last = null;
    for (int i = 0; i < overpass.size(); i++) {
      work.remaining();
      HttpUrl url =
          Objects.requireNonNull(HttpUrl.parse(overpass.get(i)))
              .newBuilder()
              .addQueryParameter("data", query)
              .build();
      long at = System.nanoTime();
      String label = "节点" + (i + 1) + "（" + url.host() + "）";
      try {
        var result =
            fetch(
                new Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "TravelStudyAssistant/0.3 (educational travel planner; user initiated,"
                            + " cached)")
                    .build(),
                12000);
        if (overpass.size() > 1) work.trace("place_source", "ok", at, label + "查询完成");
        return result;
      } catch (org.example.traveljavaserver.common.AppException e) {
        if (overpass.size() > 1)
          work.trace("place_source", "error", at, label + "：" + e.getMessage());
        boolean retryable =
            e instanceof RetryableSourceFailure
                || Set.of("TOOL_TIMEOUT", "TOOL_CONNECTION_ERROR").contains(e.getErrorCode());
        if (!retryable) throw e;
        last = e;
        if (i + 1 < overpass.size()) work.progress = "景点节点暂时不可用，正在切换备用节点";
      }
    }
    if (overpass.size() == 1) throw last;
    throw Work.error(
        last.getErrorCode(), "景点查询未完成，已尝试" + overpass.size() + "个公开节点：" + last.getMessage());
  }

  private JsonNode fetch(Request request, long timeoutMs) {
    Work work = Work.current();
    work.use();
    Call call = http.newCall(request);
    call.timeout().timeout(Math.min(timeoutMs, work.remaining()), TimeUnit.MILLISECONDS);
    work.track(call);
    try (Response response = call.execute()) {
      if (!response.isSuccessful()) {
        String message = "公开数据接口暂时不可用（HTTP " + response.code() + "），请稍后重试";
        if (response.code() >= 500) throw new RetryableSourceFailure(message);
        throw Work.error("TOOL_HTTP_ERROR", message);
      }
      if (response.body() == null) throw Work.error("TOOL_INVALID_RESPONSE", "公开数据接口返回了空内容");
      return json.readTree(response.body().string());
    } catch (java.io.IOException e) {
      if (work.cancelled.get()) throw new java.util.concurrent.CancellationException();
      work.remaining();
      if (e instanceof javax.net.ssl.SSLException)
        throw Work.error("TOOL_TLS_ERROR", "数据服务证书校验失败，请检查JDK受信任证书或使用项目Windows启动脚本");
      throw Work.error(
          e instanceof java.io.InterruptedIOException ? "TOOL_TIMEOUT" : "TOOL_CONNECTION_ERROR",
          "公开数据查询失败或超时，请稍后重试");
    } finally {
      work.calls.remove(call);
    }
  }

  private boolean fresh(PlanTypes.Places value) {
    if (value == null) return false;
    try {
      long age =
          java.time.Duration.between(Instant.parse(value.queriedAt()), Instant.now()).toSeconds();
      return age >= 0 && age < cacheTtlSeconds;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private PlanTypes.Places readCache(String city) {
    if (cacheDir == null) return null;
    try {
      var value =
          json.readValue(
              Files.readString(cacheDir.resolve(city + ".json")), PlanTypes.Places.class);
      if (!city.equals(value.city())
          || !fresh(value)
          || value.items() == null
          || value.items().size() < 3) return null;
      return value;
    } catch (java.io.IOException | RuntimeException e) {
      return null;
    }
  }

  private void writeCache(PlanTypes.Places value) {
    if (cacheDir == null) return;
    Path temporary = null;
    try {
      Files.createDirectories(cacheDir);
      temporary = Files.createTempFile(cacheDir, "places-", ".tmp");
      Files.writeString(temporary, json.writeValueAsString(value));
      Files.move(
          temporary, cacheDir.resolve(value.city() + ".json"), StandardCopyOption.REPLACE_EXISTING);
    } catch (java.io.IOException e) {
      /* Cache writes must not turn a successful source query into failure. */
    } finally {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (java.io.IOException ignored) {
        }
    }
  }

  void clearCache() {
    cache.clear();
  }

  @PreDestroy
  public void close() {
    http.dispatcher().cancelAll();
    http.dispatcher().executorService().shutdownNow();
    http.connectionPool().evictAll();
  }
}

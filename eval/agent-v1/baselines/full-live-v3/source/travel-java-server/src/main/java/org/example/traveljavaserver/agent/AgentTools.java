package org.example.traveljavaserver.agent;

import java.math.BigDecimal;
import java.util.*;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

final class AgentTools {
  private final AgentRun run;
  private final AgentMaps maps;
  final Map<String, AgentMaps.Place> places = new HashMap<>();
  private final Map<String, AgentMaps.Route> routes = new HashMap<>();
  TravelRecommendVO accepted;
  int validations, estimates;
  AgentTools(AgentRun run, AgentMaps maps) { this.run = run; this.maps = maps; }

  @Tool(name = "search_places", description = "按关键词查询本次目的地的高德地点，返回真实地点ID及查询时间。未配置或失败会返回错误，不能编造来源。")
  public Object search(@ToolParam(description = "例如博物馆、公园，最多80字") String keywords) {
    var result = maps.search(run, keywords);
    result.forEach(p -> places.put(p.id(), p));
    return Map.of("source", "高德地图", "places", result);
  }
  @Tool(name = "route_places", description = "查询本次search_places返回的两个地点之间的路线。自动使用任务选定的步行、公交含地铁或自驾方式；返回距离、时长、公交换乘摘要及可用的费用说明。必须先取得地点ID，不能更改出行方式。")
  public Object route(String fromPlaceId, String toPlaceId) {
    var from = places.get(fromPlaceId); var to = places.get(toPlaceId);
    if (from == null || to == null || fromPlaceId.equals(toPlaceId))
      throw new IllegalArgumentException("请使用本次地点查询得到的两个不同ID");
    var result = maps.route(run, from, to);
    routes.put(fromPlaceId + ":" + toPlaceId, result);
    return result;
  }
  @Tool(name = "estimate_budget", description = "由Java计算单人目的地内5项估算费用之和、预算余额及是否超预算。生成草稿前必须调用；不含往返目的地大交通。")
  public Object estimate(BigDecimal accommodation, BigDecimal food, BigDecimal transportation,
      BigDecimal tickets, BigDecimal other) {
    var values = Arrays.asList(accommodation, food, transportation, tickets, other);
    if (values.stream().anyMatch(v -> !AgentRules.money(v))) throw new IllegalArgumentException("费用须为非负金额，最多两位小数");
    estimates++;
    var total = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
    return Map.of("estimatedTotal", total, "remainingBudget", run.start.budget().subtract(total),
        "withinBudget", total.compareTo(run.start.budget()) <= 0, "scope", AgentRules.SCOPE);
  }
  @Tool(name = "validate_itinerary", description = "提交草稿JSON字符串进行结构、预算、重复景点、每日时长和修改范围校验。valid=false时读取issues并修正后再次调用。最终必须有一次valid=true，才能交付草稿。")
  public Object validate(@ToolParam(description = "创建时为完整TravelPlan JSON；局部修改时只包含指定时段对象及可选budgetBreakdown，格式见任务说明") String draftJson) {
    accepted = null;
    if (++validations > 4) throw AgentRun.error("AGENT_REPAIR_LIMIT", "草稿连续校验未通过，请简化要求后重试");
    if (estimates == 0) {
      run.report = new AgentTypes.Report(false, List.of("请先调用estimate_budget核算费用"), List.of(), null, null);
      return run.report;
    }
    try {
      var plan = AgentRules.candidate(run, draftJson);
      sanitize(plan);
      var report = AgentRules.validate(run, plan);
      run.report = report;
      if (report.valid()) {
        plan.setEstimatedTotal(report.estimatedTotal());
        plan.setRemainingBudget(report.remainingBudget());
        plan.setWarnings(report.warnings());
        accepted = plan;
      }
      return report;
    } catch (org.example.traveljavaserver.common.AppException e) { throw e; }
    catch (Exception e) {
      var report = new AgentTypes.Report(false,
          List.of(e instanceof IllegalArgumentException ? e.getMessage() : "草稿JSON格式或字段类型不正确，请按任务指定格式提交"),
          List.of(), null, null);
      run.report = report;
      return report;
    }
  }
  private void sanitize(TravelRecommendVO plan) {
    plan.setEvidence(null); plan.setAgentEvidence(null); plan.setRawResponse(null); plan.setError(null);
    plan.setTravelMode(run.start.travelMode());
    if (plan.getDailyItinerary() == null) return;
    for (var day : plan.getDailyItinerary()) {
      if (day == null) continue;
      for (String name : AgentRules.SLOTS) {
        var slot = AgentRules.slot(day, name);
        if (slot == null) continue;
        if (run.revision != null && (day.getDay() == null || day.getDay() != run.revision.day()
            || !AgentRules.targets(run).contains(name))) continue;
        var place = queriedPlace(slot);
        if (slot.getVisitMinutes() != null) slot.setDuration(slot.getVisitMinutes() + "分钟");
        if (slot.getEstimatedCost() != null) slot.setTicket("门票预留" + slot.getEstimatedCost() + "元（估算，未核实）");
        if (slot.getTravelMinutes() != null)
          slot.setTransportation(TravelModes.label(run.start.travelMode()) + "约"
              + slot.getTravelMinutes() + "分钟（参考估算，路线未查询）");
        slot.setPlaceId(null); slot.setLatitude(null); slot.setLongitude(null);
        slot.setSourceUrl(null); slot.setQueriedAt(null); slot.setRoute(null);
        if (place != null) {
          slot.setSpot(place.name()); slot.setPlaceId(place.id()); slot.setLatitude(place.latitude());
          slot.setLongitude(place.longitude()); slot.setQueriedAt(place.queriedAt());
          slot.setSourceUrl("https://www.amap.com/detail/" + java.net.URLEncoder.encode(place.id(), java.nio.charset.StandardCharsets.UTF_8));
        }
      }
      String previous = null;
      for (String name : AgentRules.SLOTS) {
        var slot = AgentRules.slot(day, name);
        if (slot == null) continue;
        boolean changed = run.revision == null || (day.getDay() != null && day.getDay() == run.revision.day()
            && AgentRules.targets(run).contains(name));
        var route = routes.get(previous + ":" + slot.getPlaceId());
        if (changed && route != null) {
          slot.setTravelMinutes(route.minutes());
          String description = "高德" + TravelModes.label(route.profile()) + "路线："
              + route.minutes() + "分钟，" + String.format(Locale.ROOT, "%.2f", route.meters() / 1000) + "公里";
          if (!route.summary().isBlank()) description += "；" + route.summary();
          if (route.estimatedFare() != null && route.profile().equals("transit"))
            description += "；票价参考" + route.estimatedFare().toPlainString() + "元";
          slot.setTransportation(description + "；" + route.fareNotice());
          slot.setRoute(new org.example.traveljavaserver.planning.PlanTypes.Leg(route.fromId(),
              route.toId(), route.meters(), route.seconds(), route.profile(), "高德地图",
              route.queriedAt(), route.estimatedFare()));
        }
        previous = slot.getPlaceId();
      }
    }
  }

  /** A model can omit the ID while retaining the exact name returned by a tool.
   * Only hydrate from a unique, exact match in this run; never guess from a partial name. */
  private AgentMaps.Place queriedPlace(TravelRecommendVO.Timeslot slot) {
    String id = slot.getPlaceId();
    if (id != null && !id.isBlank()) return places.get(id);
    if (slot.getSpot() == null) return null;
    var matches = places.values().stream()
        .filter(p -> p.name().equals(slot.getSpot().trim())).toList();
    return matches.size() == 1 ? matches.get(0) : null;
  }
}


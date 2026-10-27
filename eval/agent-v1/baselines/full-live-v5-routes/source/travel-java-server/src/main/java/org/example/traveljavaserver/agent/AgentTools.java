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
  private final Map<String, String> unavailableRoutes = new HashMap<>();
  private final Set<String> searchedNames = new HashSet<>();
  AgentTypes.Refusal blockedRoute;
  String mapFailure;
  TravelRecommendVO accepted;
  int validations, estimates;
  AgentTools(AgentRun run, AgentMaps maps) { this.run = run; this.maps = maps; run.routesRequired = maps.configured(); }

  @Tool(name = "search_places", description = "按关键词查询本次目的地的高德地点，返回真实地点ID及查询时间。未配置或失败会返回错误，不能编造来源。")
  public Object search(@ToolParam(description = "例如博物馆、公园，最多80字") String keywords) {
    List<AgentMaps.Place> result;
    try { result = maps.search(run, keywords); }
    catch (AgentMaps.ServiceFailure e) { mapFailure = e.getMessage(); throw e; }
    result.forEach(p -> places.put(p.id(), p));
    return Map.of("source", "高德地图", "places", result);
  }
  @Tool(name = "route_places", description = "查询本次search_places返回的两个地点之间的路线。自动使用任务选定的步行、公交含地铁或自驾方式；返回距离、时长、公交换乘摘要及可用的费用说明。必须先取得地点ID，不能更改出行方式。")
  public Object route(String fromPlaceId, String toPlaceId) {
    var from = places.get(fromPlaceId); var to = places.get(toPlaceId);
    if (from == null || to == null || fromPlaceId.equals(toPlaceId))
      throw new IllegalArgumentException("请使用本次地点查询得到的两个不同ID");
    return verifiedRoute(from, to);
  }
  @Tool(name = "report_infeasible", description = "无法满足预算或用户硬约束时，明确向用户交付不可行原因并结束本次任务，不会保存行程。reason须说明具体约束；不能把服务报错伪装成无路线，不能宣称未查询的路线可用。")
  public Object refuse(@ToolParam(description = "具体不可行原因，最多600字") String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 600)
      throw new IllegalArgumentException("请提供1～600字的具体不可行原因");
    accepted = null;
    run.draftAccepted = false;
    if (mapFailure != null) throw mapUnavailable();
    throw new AgentDeclined("MODEL_DECLINED", reason.trim());
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
    run.draftAccepted = false;
    if (++validations > 4) {
      if (mapFailure != null) throw mapUnavailable();
      if (blockedRoute != null) throw new AgentDeclined(blockedRoute.code(), blockedRoute.reason());
      throw AgentRun.error("AGENT_REPAIR_LIMIT", "草稿连续校验未通过，请根据校验问题调整要求后重试");
    }
    blockedRoute = null; mapFailure = null; run.requiredRoutes.clear(); run.verifiedRoutes.clear();
    if (estimates == 0) {
      run.report = new AgentTypes.Report(false, List.of("请先调用estimate_budget核算费用"), List.of(), null, null);
      return run.report;
    }
    try {
      var plan = AgentRules.candidate(run, draftJson);
      if (!org.example.traveljavaserver.service.PlanStructure.complete(plan)) {
        run.report = AgentRules.validate(run, plan); return run.report;
      }
      sanitize(plan);
      var report = AgentRules.validate(run, plan);
      run.report = report;
      if (report.valid()) {
        plan.setEstimatedTotal(report.estimatedTotal());
        plan.setRemainingBudget(report.remainingBudget());
        plan.setWarnings(report.warnings());
        accepted = plan;
        run.draftAccepted = true;
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
    for (int index = 0; index < plan.getDailyItinerary().size(); index++) {
      var day = plan.getDailyItinerary().get(index);
      if (day == null) continue;
      boolean inspect = maps.configured() && (run.revision == null || index == run.revision.day() - 1);
      var resolved = new HashMap<String, AgentMaps.Place>();
      for (String name : AgentRules.SLOTS) {
        var slot = AgentRules.slot(day, name);
        if (slot == null) continue;
        boolean changed = changed(index, name);
        var place = inspect && !AgentRules.rest(slot) ? resolvePlace(slot) : queriedPlace(slot);
        if (place != null) resolved.put(name, place);
        if (!changed) continue;
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
        if (slot == null || AgentRules.rest(slot)) continue;
        boolean affected = AgentRules.affectedRoute(run, index, name, previous);
        if (affected && previous == null && slot.getRoute() != null) {
          slot.setRoute(null); slot.setTravelMinutes(0); slot.setTransportation("首站交通未计入相邻景点路段");
        }
        if (affected && previous != null && inspect) {
          String edge = (index + 1) + ":" + name; run.requiredRoutes.add(edge);
          var from = resolved.get(previous); var to = resolved.get(name);
          if (from == null || to == null) throw new IllegalArgumentException("无法唯一确认相邻景点，请使用地图查询返回的完整地点名称");
          AgentMaps.Route route;
          try { route = automatic("verify_route", () -> verifiedRoute(from, to)); }
          catch (AgentMaps.NoRoute e) {
            blockedRoute = new AgentTypes.Refusal("ROUTE_UNAVAILABLE", e.getMessage());
            throw e;
          }
          run.verifiedRoutes.put(edge, route);
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
        } else if (affected && previous != null && !maps.configured() && !changed(index, name) && slot.getRoute() != null) {
          slot.setRoute(null); slot.setTravelMinutes(null);
          slot.setTransportation("相邻地点已调整，路线未核实；需确认新的交通耗时");
        }
        previous = name;
      }
    }
    if (run.revision != null) reconcileCosts(plan);
  }

  private boolean changed(int day, String name) {
    return AgentRules.changed(run, day, name);
  }
  private AgentMaps.Place resolvePlace(TravelRecommendVO.Timeslot slot) {
    var place = queriedPlace(slot);
    if (place == null && slot.getSpot() != null && !slot.getSpot().isBlank() && searchedNames.add(slot.getSpot())) {
      String keyword = slot.getSpot().trim();
      try { automatic("verify_place", () -> search(keyword.substring(0, Math.min(80, keyword.length())))); }
      catch (RuntimeException e) { searchedNames.remove(slot.getSpot()); throw e; }
      place = queriedPlace(slot);
    }
    if (place == null) throw new IllegalArgumentException("无法唯一确认地点：" + slot.getSpot() + "；请使用地图返回的完整地点名称");
    return place;
  }
  private AgentMaps.Route verifiedRoute(AgentMaps.Place from, AgentMaps.Place to) {
    String key = from.id() + ":" + to.id();
    if (unavailableRoutes.containsKey(key)) throw new AgentMaps.NoRoute(unavailableRoutes.get(key));
    if (routes.containsKey(key)) return routes.get(key);
    try {
      var result = maps.route(run, from, to); routes.put(key, result); return result;
    } catch (AgentMaps.NoRoute e) { unavailableRoutes.put(key, e.getMessage()); throw e; }
    catch (AgentMaps.ServiceFailure e) { mapFailure = e.getMessage(); throw e; }
  }
  org.example.traveljavaserver.common.AppException mapUnavailable() {
    return AgentRun.error("MAP_SERVICE_UNAVAILABLE", "地图服务暂时无法核实路线，请稍后重试；不能据此认定没有可用交通方案");
  }
  private <T> T automatic(String operation, java.util.function.Supplier<T> action) {
    long at = System.nanoTime(); run.remaining();
    try { T value = action.get(); run.trace(operation, "ok", at, "后端核验已完成"); return value; }
    catch (RuntimeException e) { run.trace(operation, "error", at, e instanceof IllegalArgumentException ? e.getMessage() : "核验未完成"); throw e; }
  }
  private void reconcileCosts(TravelRecommendVO plan) {
    var budget = plan.getBudgetBreakdown(); if (budget == null) return;
    BigDecimal tickets = BigDecimal.ZERO, fares = BigDecimal.ZERO;
    for (var day : plan.getDailyItinerary()) for (String name : AgentRules.SLOTS) {
      var slot = AgentRules.slot(day, name);
      if (slot == null || !AgentRules.money(slot.getEstimatedCost())) return;
      tickets = tickets.add(slot.getEstimatedCost());
      if (slot.getRoute() != null && AgentRules.money(slot.getRoute().estimatedFare()))
        fares = fares.add(slot.getRoute().estimatedFare());
    }
    budget.setTickets(tickets.setScale(2));
    if (AgentRules.money(budget.getTransportation())) budget.setTransportation(budget.getTransportation().max(fares));
  }

  /** A model can omit the ID while retaining the exact name returned by a tool.
   * Only hydrate from a unique, exact match in this run; never guess from a partial name. */
  private AgentMaps.Place queriedPlace(TravelRecommendVO.Timeslot slot) {
    String id = slot.getPlaceId();
    if (id != null && !id.isBlank() && places.containsKey(id)) return places.get(id);
    if (slot.getSpot() == null) return null;
    var matches = places.values().stream()
        .filter(p -> p.name().equals(slot.getSpot().trim())).toList();
    return matches.size() == 1 ? matches.get(0) : null;
  }
}


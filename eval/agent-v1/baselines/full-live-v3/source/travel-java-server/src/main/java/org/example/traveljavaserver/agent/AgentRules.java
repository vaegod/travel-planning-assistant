package org.example.traveljavaserver.agent;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.util.*;
import org.example.traveljavaserver.knowledge.CityCatalog;
import org.example.traveljavaserver.planning.PlanTypes;
import org.example.traveljavaserver.service.PlanStructure;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.example.traveljavaserver.vo.TravelRecommendVO.*;

final class AgentRules {
  static final ObjectMapper JSON = new ObjectMapper()
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
  static final List<String> SLOTS = List.of("morning", "afternoon", "evening");
  static final String SCOPE = "单人目的地内预算（含住宿、餐饮、景点、当地交通和其他）；不含往返目的地大交通。所有未查询费用均为估算。";
  private AgentRules() {}
  static TravelRecommendVO copy(TravelRecommendVO p) { return JSON.convertValue(p, TravelRecommendVO.class); }
  static Timeslot slot(DailyItinerary day, String name) {
    return switch (name) { case "morning" -> day.getMorning(); case "afternoon" -> day.getAfternoon(); default -> day.getEvening(); };
  }
  static void set(DailyItinerary day, String name, Timeslot slot) {
    switch (name) { case "morning" -> day.setMorning(slot); case "afternoon" -> day.setAfternoon(slot); default -> day.setEvening(slot); }
  }
  static Set<String> targets(AgentRun run) {
    return run.revision.slot().equals("day") ? Set.copyOf(SLOTS) : Set.of(run.revision.slot());
  }
  static TravelRecommendVO candidate(AgentRun run, String text) throws Exception {
    if (text == null || text.length() > 150000) throw new IllegalArgumentException("草稿内容过长");
    JsonNode node = JSON.readTree(text);
    if (node == null || !node.isObject()) throw new IllegalArgumentException("草稿必须是JSON对象");
    if (run.revision == null) return JSON.treeToValue(node, TravelRecommendVO.class);
    var allowed = new HashSet<>(targets(run));
    allowed.add("budgetBreakdown");
    var fields = node.fieldNames();
    while (fields.hasNext())
      if (!allowed.contains(fields.next())) throw new IllegalArgumentException("修改草稿只能包含选定时段和budgetBreakdown，不可修改其他日期或预算上限");
    var plan = copy(run.base);
    var day = plan.getDailyItinerary().get(run.revision.day() - 1);
    for (String name : targets(run)) {
      if (!node.path(name).isObject()) throw new IllegalArgumentException("请提供指定时段：" + name);
      set(day, name, JSON.treeToValue(node.path(name), Timeslot.class));
    }
    if (node.has("budgetBreakdown")) {
      var costs = JSON.treeToValue(node.path("budgetBreakdown"), BudgetBreakdown.class);
      var old = run.base.getBudgetBreakdown();
      if (costs == null || old == null || !equal(costs.getAccommodation(), old.getAccommodation())
          || !equal(costs.getFood(), old.getFood()) || !equal(costs.getOther(), old.getOther()))
        throw new IllegalArgumentException("局部修改只能调整门票和当地交通费用，住宿、餐饮、其他费用保持原值");
      plan.setBudgetBreakdown(costs);
    }
    return plan;
  }
  static boolean equal(BigDecimal a, BigDecimal b) { return a != null && b != null && a.compareTo(b) == 0; }
  static boolean money(BigDecimal n) {
    return n != null && n.signum() >= 0 && n.compareTo(new BigDecimal("1000000")) <= 0
        && n.stripTrailingZeros().scale() <= 2;
  }
  static List<BigDecimal> costs(BudgetBreakdown b) {
    return b == null ? Collections.nCopies(5, null) :
        Arrays.asList(b.getAccommodation(), b.getFood(), b.getTransportation(), b.getTickets(), b.getOther());
  }
  static AgentTypes.Report validate(AgentRun run, TravelRecommendVO plan) {
    var errors = new ArrayList<String>();
    var warnings = new LinkedHashSet<String>();
    warnings.add(SCOPE);
    if (run.start.travelMode().equals("transit"))
      warnings.add("公交方案可能包含地铁、换乘与接驳步行；未指定出发时刻，班次及运营时间请在出行前确认");
    if (run.start.travelMode().equals("driving"))
      warnings.add("自驾耗时为查询时的路线参考；通行费不含油费、停车费等，未来路况及费用需另行确认");
    if (run.revision != null) warnings.add("局部修改仅替换选定时段，相邻时段的既有交通建议需重新核对衔接");
    if (!PlanStructure.complete(plan))
      return new AgentTypes.Report(false, List.of("天数、每日序号和早中晚安排必须完整"), List.copyOf(warnings), null, null);
    if (!Boolean.TRUE.equals(plan.getSuccess()) || plan.getCity() == null
        || !CityCatalog.canonical(run.start.city()).equals(CityCatalog.canonical(plan.getCity()))
        || plan.getDays() != run.start.days() || !equal(plan.getTotalBudget(), run.start.budget()))
      errors.add("城市、天数和预算上限必须与用户要求一致");
    if (!run.start.travelMode().equals(plan.getTravelMode())) errors.add("出行方式必须与本次选择一致");
    Set<String> names = new HashSet<>();
    BigDecimal knownTickets = BigDecimal.ZERO;
    BigDecimal knownTransport = BigDecimal.ZERO;
    boolean allCosts = true;
    for (var day : plan.getDailyItinerary()) {
      int minutes = 0;
      boolean completeTime = true;
      for (String name : SLOTS) {
        var s = slot(day, name);
        boolean changed = run.revision == null ||
            (day.getDay() == run.revision.day() && targets(run).contains(name));
        if (s.getRoute() != null) {
          if (changed && !run.start.travelMode().equals(s.getRoute().profile()))
            errors.add("路线方式与本次选择不一致：" + s.getSpot());
          if (money(s.getRoute().estimatedFare())) knownTransport = knownTransport.add(s.getRoute().estimatedFare());
        }
        if (s.getSpot().length() > 120 || length(s.getDescription()) > 2000
            || length(s.getTransportation()) > 500 || length(s.getTicket()) > 300 || length(s.getDuration()) > 100)
          errors.add("第" + day.getDay() + "天的安排文字过长");
        boolean rest = Boolean.TRUE.equals(s.getRest()) && s.getSpot().matches(".*(休息|自由|用餐|酒店|住宿).*");
        String normalized = s.getSpot().replaceAll("[\\s·（）()]", "").toLowerCase(Locale.ROOT);
        if (!rest && !names.add(normalized)) errors.add("景点重复：" + s.getSpot());
        if (s.getVisitMinutes() == null || s.getTravelMinutes() == null) {
          completeTime = false;
          if (changed) errors.add("新安排需提供visitMinutes和travelMinutes：" + s.getSpot());
          else warnings.add("部分原行程缺少时长数值，无法完整校验这些日期的总时长");
        }
        if (s.getVisitMinutes() != null) {
          if (s.getVisitMinutes() < 15 || s.getVisitMinutes() > 360) errors.add("游览时长须为15～360分钟：" + s.getSpot());
          else minutes += s.getVisitMinutes();
        }
        if (s.getTravelMinutes() != null) {
          if (s.getTravelMinutes() < 0 || s.getTravelMinutes() > 240) errors.add("交通时长须为0～240分钟：" + s.getSpot());
          else minutes += s.getTravelMinutes();
        }
        if (s.getEstimatedCost() == null) {
          allCosts = false;
          if (changed) errors.add("新安排需提供estimatedCost（单人门票估算）：" + s.getSpot());
        } else if (!money(s.getEstimatedCost())) errors.add("门票费用必须非负且最多两位小数");
        else knownTickets = knownTickets.add(s.getEstimatedCost());
        if (s.getPlaceId() == null) warnings.add("部分地点和交通未通过地图查询核实，时长、票价、开放及预约条件需另行确认");
      }
      if (minutes > 600) errors.add("第" + day.getDay() + "天游览与交通合计超过600分钟");
      if (!completeTime) warnings.add("时间校验仅覆盖提供了分钟数的安排");
    }
    var values = costs(plan.getBudgetBreakdown());
    BigDecimal total = null, remaining = null;
    if (values.stream().anyMatch(v -> !money(v))) errors.add("预算明细必须包含5项非负金额，每项最多两位小数");
    else {
      total = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
      remaining = run.start.budget().subtract(total);
      if (total.signum() <= 0) errors.add("预计花费不能为零");
      if (remaining.signum() < 0) errors.add("预计花费" + total + "元超过预算" + run.start.budget() + "元");
      if ((allCosts && knownTickets.compareTo(plan.getBudgetBreakdown().getTickets()) != 0)
          || knownTickets.compareTo(plan.getBudgetBreakdown().getTickets()) > 0)
        errors.add("预算门票合计与时段门票估算不一致");
      if (knownTransport.compareTo(plan.getBudgetBreakdown().getTransportation()) > 0)
        errors.add("交通预算低于已查询路段的参考票价合计" + knownTransport + "元");
    }
    return new AgentTypes.Report(errors.isEmpty(), List.copyOf(new LinkedHashSet<>(errors)),
        List.copyOf(warnings), total, remaining);
  }
  private static int length(String value) { return value == null ? 0 : value.length(); }
  static List<PlanTypes.Change> diff(TravelRecommendVO before, TravelRecommendVO after) {
    if (before == null) return List.of();
    var result = new ArrayList<PlanTypes.Change>();
    for (int i = 0; i < before.getDays(); i++) {
      for (String s : SLOTS) {
        var left = slot(before.getDailyItinerary().get(i), s);
        var right = slot(after.getDailyItinerary().get(i), s);
        if (!JSON.valueToTree(left).equals(JSON.valueToTree(right)))
          result.add(new PlanTypes.Change("dailyItinerary[" + i + "]." + s,
              "第" + (i + 1) + "天 · " + Map.of("morning", "上午", "afternoon", "下午", "evening", "晚上").get(s),
              summary(left), summary(right)));
      }
    }
    if (!JSON.valueToTree(before.getBudgetBreakdown()).equals(JSON.valueToTree(after.getBudgetBreakdown())))
      result.add(new PlanTypes.Change("budgetBreakdown", "预算明细",
          JSON.valueToTree(before.getBudgetBreakdown()).toString(), JSON.valueToTree(after.getBudgetBreakdown()).toString()));
    return List.copyOf(result);
  }
  private static String summary(Timeslot s) {
    return s.getSpot() + "；" + Objects.toString(s.getDuration(), "")
        + "；" + Objects.toString(s.getTransportation(), "") + "；" + Objects.toString(s.getTicket(), "")
        + "；" + Objects.toString(s.getDescription(), "");
  }
}


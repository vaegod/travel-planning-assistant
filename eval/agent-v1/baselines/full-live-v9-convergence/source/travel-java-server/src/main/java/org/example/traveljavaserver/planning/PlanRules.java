package org.example.traveljavaserver.planning;

import com.fasterxml.jackson.databind.*;
import java.math.*;
import java.util.*;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.example.traveljavaserver.vo.TravelRecommendVO.*;

public final class PlanRules {
  private PlanRules() {}

  public static final String EXPENSE_POLICY = "destination-fixed-expenses-v1";
  /** Existing project budgeting assumptions, not verified market prices. */
  public record FixedExpenses(BigDecimal accommodation, BigDecimal food, BigDecimal other) {
    public BigDecimal total() { return accommodation.add(food).add(other); }
  }
  public static FixedExpenses fixedExpenses(int days) {
    return new FixedExpenses(BigDecimal.valueOf(Math.max(0,days-1)*200L),
        BigDecimal.valueOf(days*80L),BigDecimal.valueOf(days*20L));
  }

  public static List<Timeslot> slots(DailyItinerary day) {
    return Arrays.asList(day.getMorning(), day.getAfternoon(), day.getEvening());
  }

  public static TravelRecommendVO copy(TravelRecommendVO plan) {
    return new ObjectMapper().convertValue(plan, TravelRecommendVO.class);
  }

  public static void calculate(TravelRecommendVO plan) {
    BigDecimal tickets = BigDecimal.ZERO, transport = BigDecimal.ZERO;
    Set<String> ids = new HashSet<>();
    if (plan.getDailyItinerary() == null || plan.getDailyItinerary().size() != plan.getDays())
      throw Work.error("INVALID_PLAN", "行程天数与每日安排不一致");
    for (int index = 0; index < plan.getDailyItinerary().size(); index++) {
      var day = plan.getDailyItinerary().get(index);
      if (day.getDay() == null || day.getDay() != index + 1)
        throw Work.error("INVALID_PLAN", "每日序号不完整");
      double minutes = 0;
      String previous = null;
      for (Timeslot slot : slots(day)) {
        if (slot == null
            || slot.getVisitMinutes() == null
            || slot.getVisitMinutes() < 30
            || slot.getVisitMinutes() > 240) throw Work.error("INVALID_PLAN", "游览时长不正确");
        minutes += slot.getVisitMinutes();
        if (slot.getEstimatedCost() == null
            || slot.getEstimatedCost().signum() < 0
            || slot.getEstimatedCost().stripTrailingZeros().scale() > 2)
          throw Work.error("INVALID_PLAN", "费用估算不正确");
        tickets = tickets.add(slot.getEstimatedCost());
        if (Boolean.TRUE.equals(slot.getRest())) continue;
        if (slot.getPlaceId() == null || !ids.add(slot.getPlaceId()))
          throw Work.error("DUPLICATE_PLACE", "行程包含缺失或重复景点");
        if (slot.getLatitude() == null
            || slot.getLongitude() == null
            || !Double.isFinite(slot.getLatitude())
            || !Double.isFinite(slot.getLongitude())
            || Math.abs(slot.getLatitude()) > 90
            || Math.abs(slot.getLongitude()) > 180
            || slot.getSourceUrl() == null
            || slot.getQueriedAt() == null) throw Work.error("INVALID_PLAN", "景点缺少有效位置或真实查询来源");
        if (previous != null) {
          var route = slot.getRoute();
          if (route == null
              || !previous.equals(route.fromId())
              || !slot.getPlaceId().equals(route.toId())
              || !Double.isFinite(route.seconds())
              || !Double.isFinite(route.meters())
              || route.seconds() < 0
              || route.meters() < 0
              || !"driving".equals(route.profile())
              || route.estimatedFare() == null
              || route.estimatedFare().signum() < 0
              || route.estimatedFare().stripTrailingZeros().scale() > 2)
            throw Work.error("INVALID_ROUTE", "景点之间缺少有效路线");
          minutes += Math.ceil(route.seconds() / 60);
          transport = transport.add(route.estimatedFare());
        }
        previous = slot.getPlaceId();
      }
      if (minutes > 600)
        throw Work.error("DAY_TOO_LONG", "第" + day.getDay() + "天游览与交通超过10小时，请调整景点");
    }
    var costs = new BudgetBreakdown();
    var fixed = fixedExpenses(plan.getDays());
    costs.setAccommodation(fixed.accommodation());
    costs.setFood(fixed.food());
    costs.setOther(fixed.other());
    costs.setTickets(tickets.setScale(2));
    costs.setTransportation(transport.setScale(2));
    BigDecimal sum =
        costs
            .getAccommodation()
            .add(costs.getFood())
            .add(costs.getOther())
            .add(tickets)
            .add(transport)
            .setScale(2);
    if (plan.getTotalBudget() == null || sum.compareTo(plan.getTotalBudget()) > 0)
      throw Work.error("BUDGET_TOO_LOW", "当前估算费用约" + sum + "元，超过预算；原行程未修改");
    plan.setBudgetBreakdown(costs);
    plan.setEstimatedTotal(sum);
    plan.setRemainingBudget(plan.getTotalBudget().subtract(sum).setScale(2));
  }

  public static List<PlanTypes.Change> diff(TravelRecommendVO before, TravelRecommendVO after) {
    List<PlanTypes.Change> output = new ArrayList<>();
    change(output, "totalBudget", "预算上限", before.getTotalBudget(), after.getTotalBudget());
    change(output, "estimatedTotal", "预计花费", before.getEstimatedTotal(), after.getEstimatedTotal());
    for (int i = 0; i < before.getDailyItinerary().size(); i++) {
      List<Timeslot> left = slots(before.getDailyItinerary().get(i)),
          right = slots(after.getDailyItinerary().get(i));
      for (int s = 0; s < 3; s++) {
        String
            key = "dailyItinerary[" + i + "]." + List.of("morning", "afternoon", "evening").get(s),
            label = "第" + (i + 1) + "天·" + List.of("上午", "下午", "晚上").get(s);
        change(output, key + ".spot", label + "景点", left.get(s).getSpot(), right.get(s).getSpot());
        change(
            output,
            key + ".visitMinutes",
            label + "时长（分钟）",
            left.get(s).getVisitMinutes(),
            right.get(s).getVisitMinutes());
      }
    }
    return List.copyOf(output);
  }

  private static void change(
      List<PlanTypes.Change> output, String path, String label, Object before, Object after) {
    if (before instanceof BigDecimal left
        && after instanceof BigDecimal right
        && left.compareTo(right) == 0) return;
    if (!Objects.equals(before, after))
      output.add(new PlanTypes.Change(path, label, String.valueOf(before), String.valueOf(after)));
  }
}

package org.example.traveljavaserver.planning;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.example.traveljavaserver.common.AppException;
import org.example.traveljavaserver.utils.LLMutils;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.example.traveljavaserver.vo.TravelRecommendVO.*;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "planning.external-enabled",
    havingValue = "true")
public class PlanningEngine {
  private final LLMutils llm;
  private final Map<String, ToolCallback> tools = new HashMap<>();
  private final ObjectMapper json = new ObjectMapper();

  public PlanningEngine(TravelTools provider, LLMutils llm) {
    this.llm = llm;
    for (var tool : ToolCallbacks.from(provider)) tools.put(tool.getToolDefinition().name(), tool);
  }

  public TravelRecommendVO execute(Work work, TravelRecommendVO base) {
    Work.CURRENT.set(work);
    try (var trace =
        org.example.traveljavaserver.utils.ModelTrace.open(
            work.id,
            org.example.traveljavaserver.utils.ModelTrace.PLAN_PROMPT,
            work.modelCalls::add)) {
      work.remaining();
      TravelRecommendVO plan;
      String action = work.revision == null ? "create" : work.revision.action();
      if (base != null
          && !action.equals("refresh")
          && (base.getEvidence() == null || !base.getEvidence().status().equals("validated")))
        throw Work.error("LEGACY_PLAN", "旧版行程缺少查询来源，请先选择更新全部景点与路线");
      if (action.equals("budget") || action.equals("relaxed")) {
        plan = PlanRules.copy(base);
        work.progress = "复用保存的景点与路线，重新核算预算";
        long at = System.nanoTime();
        work.trace("reuse_saved_evidence", "ok", at, "沿用原查询时间，不声称进行了新查询");
        if (action.equals("budget")) plan.setTotalBudget(work.revision.budget());
        else {
          var day = plan.getDailyItinerary().get(work.revision.day() - 1);
          day.getMorning().setVisitMinutes(90);
          day.getMorning().setDuration("90分钟");
          day.getAfternoon().setVisitMinutes(90);
          day.getAfternoon().setDuration("90分钟");
          day.setEvening(rest());
        }
      } else {
        work.progress = "正在查询真实景点";
        PlanTypes.Places found =
            invoke(
                work, "search_places", Map.of("city", work.start.city()), PlanTypes.Places.class);
        int replaceDay = action.equals("indoor") ? work.revision.day() : 0;
        Set<String> excluded = new HashSet<>();
        if (base != null && replaceDay > 0)
          for (var day : base.getDailyItinerary())
            if (day.getDay() != replaceDay)
              for (var slot : PlanRules.slots(day))
                if (slot.getPlaceId() != null) excluded.add(slot.getPlaceId());
        List<PlanTypes.Place> eligible =
            found.items().stream()
                .filter(p -> !excluded.contains(p.id()) && (replaceDay == 0 || p.indoor()))
                .toList();
        int count = replaceDay > 0 ? 2 : work.start.days() * 3;
        if (eligible.size() < count) throw Work.error("NOT_ENOUGH_PLACES", "满足条件的景点不足，原行程未修改");
        work.progress = replaceDay > 0 ? "AI 正在选择当天室内活动" : "AI 正在选择行程景点";
        List<String> selected = choose(work, eligible, count);
        Map<String, PlanTypes.Place> byId = new HashMap<>();
        eligible.forEach(place -> byId.put(place.id(), place));
        plan = replaceDay > 0 ? PlanRules.copy(base) : new TravelRecommendVO();
        plan.setSuccess(true);
        plan.setCity(work.start.city());
        plan.setDays(work.start.days());
        plan.setTotalBudget(work.start.budget());
        List<DailyItinerary> days = replaceDay > 0 ? plan.getDailyItinerary() : new ArrayList<>();
        List<Timeslot> newSlots = new ArrayList<>();
        for (String id : selected) newSlots.add(slot(byId.get(id)));
        work.progress = "正在查询 OSRM 驾车路线";
        PlanTypes.Routes routes =
            invoke(
                work,
                "route_places",
                Map.of("city", work.start.city(), "placeIds", selected),
                PlanTypes.Routes.class);
        if (routes.legs().size() != selected.size() - 1)
          throw Work.error("INVALID_ROUTE_RESPONSE", "路线数量不正确");
        for (int i = 1; i < newSlots.size(); i++)
          if (replaceDay > 0 || i % 3 != 0) {
            var route = routes.legs().get(i - 1);
            newSlots.get(i).setRoute(route);
            newSlots
                .get(i)
                .setTransportation(
                    "OSRM驾车估算："
                        + Math.round(route.seconds() / 60)
                        + "分钟，"
                        + String.format(java.util.Locale.ROOT, "%.1f", route.meters() / 1000)
                        + "公里；非实时交通");
          }
        if (replaceDay > 0) {
          var day = new DailyItinerary();
          day.setDay(replaceDay);
          day.setDate("第" + replaceDay + "天");
          day.setMorning(newSlots.get(0));
          day.setAfternoon(newSlots.get(1));
          day.setEvening(rest());
          days.set(replaceDay - 1, day);
        } else
          for (int i = 0; i < work.start.days(); i++) {
            var day = new DailyItinerary();
            day.setDay(i + 1);
            day.setDate("第" + (i + 1) + "天");
            day.setMorning(newSlots.get(i * 3));
            day.setAfternoon(newSlots.get(i * 3 + 1));
            day.setEvening(newSlots.get(i * 3 + 2));
            days.add(day);
          }
        plan.setDailyItinerary(days);
      }
      work.progress = "正在检查天数、重复景点、时长与预算";
      PlanRules.calculate(plan);
      work.remaining();
      plan.setTips(
          List.of(
              "预算按单人目的地内支出估算；住宿200元/晚、餐饮80元/天、其他20元/天。",
              "票价均未核实，每个景点暂预留40元；驾车交通费用按每段10元+每公里2元估算，并非报价。"));
      plan.setWarnings(
          List.of(
              "公开地图和路线不保证实时准确；开放时间、门票与预约需自行核实。",
              "OSRM仅为驾车路网估算，不是步行、公交或实时路况；首站及返回住宿地交通未包含。",
              "室内分类根据地图标签推断，不代表已核实当日开放条件；晚间场所需另行核实。"));
      plan.setEvidence(
          new PlanTypes.Evidence(
              "validated",
              "© OpenStreetMap contributors / ODbL · OSRM",
              "票价未知，所有费用为明确假设下的估算",
              List.copyOf(work.traces),
              List.copyOf(work.modelCalls)));
      return plan;
    } finally {
      Work.CURRENT.remove();
    }
  }

  private List<String> choose(Work work, List<PlanTypes.Place> options, int count) {
    String prompt =
        "目的地"
            + work.start.city()
            + "，"
            + work.start.days()
            + "天，预算上限"
            + work.start.budget()
            + "元。请选择"
            + count
            + "个不重复地点，按访问顺序输出。尽量减少距离，不要选择未给出的ID。只输出JSON：{\"placeIds\":[\"node/123\"]}。候选："
            + encode(
                options.stream()
                    .map(
                        p ->
                            Map.of(
                                "id",
                                p.id(),
                                "name",
                                p.name(),
                                "lat",
                                p.latitude(),
                                "lon",
                                p.longitude(),
                                "indoor",
                                p.indoor()))
                    .toList())
            + (work.revision == null
                ? ""
                : "。用户补充：" + Objects.toString(work.revision.instruction(), ""));
    Set<String> allowed = new HashSet<>();
    options.forEach(p -> allowed.add(p.id()));
    for (int attempt = 0; attempt < 2; attempt++) {
      work.use();
      long started = System.nanoTime();
      okhttp3.Call call = llm.newJsonChatCall("你是行程景点选择助手，只能从候选ID中选择，输出严格JSON。", prompt);
      call.timeout().timeout(Math.min(120000, work.remaining()), TimeUnit.MILLISECONDS);
      work.track(call);
      String text;
      try {
        text = llm.readCompletion(call);
      } catch (RuntimeException e) {
        work.trace("model_select", "error", started, "模型请求未完成，未生成替代结果");
        work.remaining();
        throw e;
      } finally {
        work.calls.remove(call);
      }
      try {
        String value = text.trim();
        if (value.startsWith("```"))
          value = value.substring(value.indexOf('\n') + 1, value.lastIndexOf("```")).trim();
        JsonNode ids = json.readTree(value).path("placeIds");
        List<String> selected = new ArrayList<>();
        if (ids.isArray()) ids.forEach(id -> selected.add(id.asText()));
        if (selected.size() != count
            || new HashSet<>(selected).size() != count
            || !allowed.containsAll(selected)) throw new IllegalArgumentException();
        work.trace("model_select", "ok", started, "已校验" + count + "个候选ID");
        return selected;
      } catch (Exception e) {
        work.trace("model_select", "invalid", started, "模型选择不符合候选ID与数量约束");
        prompt += "。上次结果不合格，请严格按数量选择候选ID，不重复，不要解释。";
      }
    }
    throw Work.error("INVALID_SELECTION", "模型未能选出有效景点，请重试");
  }

  private <T> T invoke(Work work, String name, Object args, Class<T> type) {
    long started = System.nanoTime();
    try {
      work.remaining();
      String result = tools.get(name).call(encode(args));
      T value = json.readValue(result, type);
      work.trace(
          name,
          "ok",
          started,
          value instanceof PlanTypes.Places places
              ? (places.cached() ? "使用1小时缓存，原查询时间：" : "本次真实查询，时间：") + places.queriedAt()
              : "OSRM驾车路网估算");
      return value;
    } catch (Exception e) {
      work.trace(name, "error", started, "工具失败，未编造结果");
      Throwable cause = e;
      while (cause != null) {
        if (cause instanceof AppException app) throw app;
        if (cause instanceof java.util.concurrent.CancellationException cancelled) throw cancelled;
        cause = cause.getCause();
      }
      throw Work.error("TOOL_FAILED", "公开数据工具调用失败，请重试");
    }
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Timeslot slot(PlanTypes.Place place) {
    var slot = new Timeslot();
    slot.setSpot(place.name());
    slot.setPlaceId(place.id());
    slot.setLatitude(place.latitude());
    slot.setLongitude(place.longitude());
    slot.setIndoor(place.indoor());
    slot.setCategory(place.category());
    slot.setSourceUrl(place.sourceUrl());
    slot.setQueriedAt(place.queriedAt());
    slot.setVisitMinutes(120);
    slot.setDuration("120分钟");
    slot.setEstimatedCost(new BigDecimal("40.00"));
    slot.setTicket("票价未知；暂预留40元（估算）");
    slot.setTransportation("首站/当日起点交通未查询");
    slot.setDescription("来自OpenStreetMap的" + place.category() + "类别地点；开放条件未核实。");
    slot.setRest(false);
    return slot;
  }

  private Timeslot rest() {
    var slot = new Timeslot();
    slot.setSpot("住宿地自由休息");
    slot.setVisitMinutes(60);
    slot.setDuration("60分钟");
    slot.setIndoor(true);
    slot.setRest(true);
    slot.setEstimatedCost(BigDecimal.ZERO);
    slot.setTicket("不另列门票预留");
    slot.setTransportation("返回住宿地交通未包含");
    slot.setDescription("休息安排，不冒充已查询到的景点。");
    return slot;
  }
}

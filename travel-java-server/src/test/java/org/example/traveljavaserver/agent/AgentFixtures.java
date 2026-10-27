package org.example.traveljavaserver.agent;
import java.math.BigDecimal;
import java.util.*;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.example.traveljavaserver.vo.TravelRecommendVO.*;
final class AgentFixtures {
  static Timeslot slot(String spot) {
    var s = new Timeslot(); s.setSpot(spot); s.setDuration("90分钟"); s.setVisitMinutes(90);
    s.setTravelMinutes(20); s.setEstimatedCost(BigDecimal.TEN); s.setDescription("测试安排");
    s.setTransportation("交通约20分钟（估算）"); s.setTicket("预留10元（估算）"); return s;
  }
  static TravelRecommendVO plan() {
    var p = new TravelRecommendVO(); p.setSuccess(true); p.setCity("杭州"); p.setDays(2); p.setTotalBudget(new BigDecimal("1000"));
    p.setTravelMode("walking");
    var days = new ArrayList<DailyItinerary>();
    for (int i = 1; i <= 2; i++) {
      var d = new DailyItinerary(); d.setDay(i); d.setDate("第"+i+"天"); d.setMorning(slot("公园"+i));
      d.setAfternoon(slot("博物馆"+i)); d.setEvening(slot("街区"+i)); days.add(d);
    }
    p.setDailyItinerary(days);
    var b = new BudgetBreakdown(); b.setAccommodation(new BigDecimal("200")); b.setFood(new BigDecimal("160"));
    b.setTransportation(new BigDecimal("40")); b.setTickets(new BigDecimal("60")); b.setOther(new BigDecimal("40"));
    p.setBudgetBreakdown(b); return p;
  }
  static AgentRun run(AgentTypes.Revise revision) {
    return new AgentRun(1, new AgentTypes.Start(UUID.randomUUID().toString(),"杭州",2,new BigDecimal("1000"),""),
        revision, revision == null ? null : UUID.randomUUID().toString(), revision == null ? null : plan(), "fixture", 10000);
  }
  static AgentTools tools(AgentRun run) {
    var tools = new AgentTools(run, new AgentMaps("", "https://restapi.amap.com"));
    tools.estimate(new BigDecimal("200"),new BigDecimal("160"),new BigDecimal("40"),new BigDecimal("60"),new BigDecimal("40"));
    return tools;
  }
  static TravelRecommendVO mappedPlan() {
    var p=plan();p.setDays(1);p.setDailyItinerary(new ArrayList<>(List.of(p.getDailyItinerary().get(0))));
    var day=p.getDailyItinerary().get(0);day.getMorning().setSpot("测试公园");day.getMorning().setPlaceId("A");
    day.getAfternoon().setSpot("测试博物馆");day.getAfternoon().setPlaceId("B");
    var rest=slot("酒店休息");rest.setRest(true);rest.setEstimatedCost(BigDecimal.ZERO);rest.setTravelMinutes(0);day.setEvening(rest);
    p.getBudgetBreakdown().setTickets(new BigDecimal("20"));return p;
  }
  static AgentRun mappedRun(String mode) {
    return new AgentRun(1,new AgentTypes.Start(UUID.randomUUID().toString(),"杭州",1,new BigDecimal("1000"),"",mode),null,null,null,"mapped",10000);
  }
}


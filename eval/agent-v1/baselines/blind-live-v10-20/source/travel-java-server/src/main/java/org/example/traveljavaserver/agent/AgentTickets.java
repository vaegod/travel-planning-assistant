package org.example.traveljavaserver.agent;

import java.math.BigDecimal;
import java.util.*;
import org.example.traveljavaserver.vo.TravelRecommendVO;

/** Caller-supplied reference budgets. Unknown price is never evidence that a place is free. */
final class AgentTickets {
  private AgentTickets() {}
  static AgentTypes.TicketRequirement reference(AgentRun run,TravelRecommendVO.Timeslot slot) {
    return run.start.ticketRequirements().stream().filter(r -> {
      boolean hasId=r.placeId()!=null&&!r.placeId().isBlank();
      return hasId&&slot.getPlaceId()!=null?r.placeId().equals(slot.getPlaceId())
          :PlaceNames.key(r.placeName()).equals(PlaceNames.key(slot.getSpot()));
    }).max(Comparator.comparing(AgentTypes.TicketRequirement::minimumBudget)).orElse(null);
  }
  static List<String> issues(AgentRun run,TravelRecommendVO plan) {
    var issues=new ArrayList<String>();
    for(int i=0;i<plan.getDailyItinerary().size();i++)for(String name:AgentRules.SLOTS) {
      var slot=AgentRules.slot(plan.getDailyItinerary().get(i),name);
      if(!AgentRules.changed(run,i,name)||AgentRules.rest(slot)||!AgentRules.money(slot.getEstimatedCost()))continue;
      var ref=reference(run,slot);
      if(ref!=null&&slot.getEstimatedCost().compareTo(ref.minimumBudget())<0)
        issues.add("dailyItinerary["+i+"]."+name+".estimatedCost："+slot.getSpot()+"门票预留至少"
            +ref.minimumBudget().toPlainString()+"元（请求参考："+ref.source()+"），当前"
            +slot.getEstimatedCost().toPlainString()+"元；修正后重算budgetBreakdown.tickets和总预算");
    }
    return List.copyOf(issues);
  }
}

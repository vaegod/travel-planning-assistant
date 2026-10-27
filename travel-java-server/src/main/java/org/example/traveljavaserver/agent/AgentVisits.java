package org.example.traveljavaserver.agent;

import java.util.*;
import org.example.traveljavaserver.vo.TravelRecommendVO;

/** User/caller supplied reference bounds, kept outside model-editable drafts. Not Amap facts. */
final class AgentVisits {
  private AgentVisits() {}
  static int minimum(AgentRun run,TravelRecommendVO.Timeslot slot) {
    int floor=15;
    for(var requirement:run.start.visitRequirements()) {
      boolean hasId=requirement.placeId()!=null&&!requirement.placeId().isBlank();
      boolean match=hasId&&slot.getPlaceId()!=null ? requirement.placeId().equals(slot.getPlaceId())
          : PlaceNames.key(requirement.placeName()).equals(PlaceNames.key(slot.getSpot()));
      if(match)floor=Math.max(floor,requirement.minimumMinutes());
    }
    return floor;
  }
  static List<String> issues(AgentRun run,TravelRecommendVO plan) {
    var issues=new ArrayList<String>();
    for(int i=0;i<plan.getDailyItinerary().size();i++)for(String name:AgentRules.SLOTS) {
      var slot=AgentRules.slot(plan.getDailyItinerary().get(i),name);
      if(!AgentRules.changed(run,i,name)||AgentRules.rest(slot)||slot.getVisitMinutes()==null)continue;
      int minimum=minimum(run,slot);
      if(slot.getVisitMinutes()<minimum)issues.add("dailyItinerary["+i+"]."+name+".visitMinutes："
          +slot.getSpot()+"须至少"+minimum+"分钟（请求提供的参考下限），当前"+slot.getVisitMinutes()
          +"分钟；请修正后重算每日游览与交通合计，仍须不超过600分钟");
    }
    return List.copyOf(issues);
  }
}

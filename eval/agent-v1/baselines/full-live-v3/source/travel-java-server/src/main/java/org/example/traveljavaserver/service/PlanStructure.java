package org.example.traveljavaserver.service;

import org.example.traveljavaserver.vo.TravelRecommendVO;

/** The same minimum shape is required for generated and client-supplied plans. */
public final class PlanStructure {
  private PlanStructure() {}
  public static boolean complete(TravelRecommendVO plan) {
    if (plan == null || plan.getDays() == null || plan.getDays() < 1 || plan.getDays() > 30
        || plan.getDailyItinerary() == null || plan.getDailyItinerary().size() != plan.getDays()) return false;
    for (int i = 0; i < plan.getDays(); i++) {
      var day = plan.getDailyItinerary().get(i);
      if (day == null || day.getDay() == null || day.getDay() != i + 1
          || !slot(day.getMorning()) || !slot(day.getAfternoon()) || !slot(day.getEvening())) return false;
    }
    return true;
  }
  private static boolean slot(TravelRecommendVO.Timeslot slot) {
    return slot != null && slot.getSpot() != null && !slot.getSpot().isBlank();
  }
}

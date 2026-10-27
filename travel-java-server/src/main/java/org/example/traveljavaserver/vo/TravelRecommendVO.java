package org.example.traveljavaserver.vo;

import java.util.List;
import lombok.Data;

@Data
public class TravelRecommendVO {
  private Boolean success;
  private String city;
  private Integer days;
  private String travelMode;
  private java.math.BigDecimal totalBudget;
  private List<DailyItinerary> dailyItinerary;
  private BudgetBreakdown budgetBreakdown;
  private List<String> tips;
  private List<String> warnings;
  private String error;
  private String rawResponse;
  private java.math.BigDecimal estimatedTotal;
  private java.math.BigDecimal remainingBudget;
  private org.example.traveljavaserver.planning.PlanTypes.Evidence evidence;
  private org.example.traveljavaserver.agent.AgentTypes.Evidence agentEvidence;

  @Data
  public static class DailyItinerary {
    private Integer day;
    private String date;
    private Timeslot morning;
    private Timeslot afternoon;
    private Timeslot evening;
  }

  @Data
  public static class Timeslot {
    private String spot;
    private String duration;
    private String transportation;
    private String description;
    private String ticket;
    private String placeId;
    private Double latitude;
    private Double longitude;
    private Boolean indoor;
    private String category;
    private String sourceUrl;
    private String queriedAt;
    private Integer visitMinutes;
    private Integer travelMinutes;
    private java.math.BigDecimal estimatedCost;
    private org.example.traveljavaserver.planning.PlanTypes.Leg route;
    private Boolean rest;
  }

  @Data
  public static class BudgetBreakdown {
    private java.math.BigDecimal accommodation;
    private java.math.BigDecimal food;
    private java.math.BigDecimal transportation;
    private java.math.BigDecimal tickets;
    private java.math.BigDecimal other;
  }
}

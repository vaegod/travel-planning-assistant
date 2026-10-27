package org.example.traveljavaserver.agent;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import org.example.traveljavaserver.planning.PlanTypes;
import org.example.traveljavaserver.utils.ModelTrace;
import org.example.traveljavaserver.vo.TravelRecommendVO;

public final class AgentTypes {
  private AgentTypes() {}
  public record VisitRequirement(@NotBlank @Size(max=120) String placeName,
      @Size(max=120) String placeId, @Min(15) @Max(360) int minimumMinutes) {}
  public record TicketRequirement(@NotBlank @Size(max=120) String placeName,
      @Size(max=120) String placeId,
      @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer=7,fraction=2) BigDecimal minimumBudget,
      @NotBlank @Size(max=160) String source) {}
  public record Start(
      @NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}") String requestId,
      @NotBlank @Size(max = 50) String city,
      @Min(1) @Max(7) int days,
      @NotNull @DecimalMin("100") @DecimalMax("1000000") @Digits(integer = 10, fraction = 2) BigDecimal budget,
      @Size(max = 1000) String instruction,
      @Pattern(regexp = "walking|transit|driving") String travelMode,
      @Size(max=100) List<@jakarta.validation.Valid VisitRequirement> visitRequirements,
      @Size(max=100) List<@jakarta.validation.Valid TicketRequirement> ticketRequirements) {
    public Start {
      travelMode = TravelModes.normalize(travelMode);
      visitRequirements = visitRequirements == null ? List.of() : List.copyOf(visitRequirements);
      ticketRequirements = ticketRequirements == null ? List.of() : List.copyOf(ticketRequirements);
    }
    public Start(String requestId,String city,int days,BigDecimal budget,String instruction,String travelMode,
        List<VisitRequirement> visitRequirements) {
      this(requestId,city,days,budget,instruction,travelMode,visitRequirements,List.of());
    }
    public Start(String requestId,String city,int days,BigDecimal budget,String instruction,String travelMode) {
      this(requestId,city,days,budget,instruction,travelMode,List.of());
    }
    public Start(String requestId, String city, int days, BigDecimal budget, String instruction) {
      this(requestId, city, days, budget, instruction, "walking");
    }
  }
  public record Revise(
      @NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}") String requestId,
      @Min(1) int baseVersion, @Min(1) @Max(30) int day,
      @NotBlank @Pattern(regexp = "day|morning|afternoon|evening") String slot,
      @NotBlank @Size(max = 1000) String instruction,
      @Size(max=100) List<@jakarta.validation.Valid VisitRequirement> visitRequirements,
      @Size(max=100) List<@jakarta.validation.Valid TicketRequirement> ticketRequirements) {
    public Revise {
      if (visitRequirements != null) visitRequirements=List.copyOf(visitRequirements);
      if (ticketRequirements != null) ticketRequirements=List.copyOf(ticketRequirements);
    }
    public Revise(String requestId,int baseVersion,int day,String slot,String instruction,List<VisitRequirement> visits) {
      this(requestId,baseVersion,day,slot,instruction,visits,null);
    }
    public Revise(String requestId,int baseVersion,int day,String slot,String instruction) {
      this(requestId,baseVersion,day,slot,instruction,null);
    }
  }
  public record Save(@Size(max = 80) String title) {}
  public record Report(boolean valid, List<String> issues, List<String> warnings,
      BigDecimal estimatedTotal, BigDecimal remainingBudget) {}
  public record Evidence(String status, String budgetScope, Report validation,
      List<PlanTypes.Trace> traces, List<ModelTrace.Entry> modelCalls, List<VisitRequirement> visitRequirements,
      List<TicketRequirement> ticketRequirements) {
    public Evidence {
      visitRequirements=visitRequirements==null?List.of():List.copyOf(visitRequirements);
      ticketRequirements=ticketRequirements==null?List.of():List.copyOf(ticketRequirements);
    }
    public Evidence(String status,String scope,Report validation,List<PlanTypes.Trace> traces,
        List<ModelTrace.Entry> calls,List<VisitRequirement> visits) {
      this(status,scope,validation,traces,calls,visits,List.of());
    }
    public Evidence(String status,String scope,Report validation,List<PlanTypes.Trace> traces,List<ModelTrace.Entry> calls) {
      this(status,scope,validation,traces,calls,List.of());
    }
  }
  public record MinimumExpenses(String policy, BigDecimal accommodation, BigDecimal food,
      BigDecimal other, BigDecimal total, BigDecimal requestedBudget) {}
  public record Refusal(String code, String reason, MinimumExpenses minimumExpenses) {
    public Refusal(String code, String reason) { this(code, reason, null); }
  }
  public record View(String id, String status, String progress, String errorCode, String message,
      String tripId, Integer baseVersion, TravelRecommendVO plan,
      List<PlanTypes.Change> changes, List<PlanTypes.Trace> traces, Integer savedVersion,
      Report validation, int modelRounds, int toolCalls, String travelMode, Refusal refusal,
      List<VisitRequirement> visitRequirements, List<TicketRequirement> ticketRequirements,
      List<ModelTrace.Entry> modelCalls, int recoveryAttempts, long recoveryDurationMs) {}
}


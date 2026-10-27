package org.example.traveljavaserver.agent;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import org.example.traveljavaserver.planning.PlanTypes;
import org.example.traveljavaserver.utils.ModelTrace;
import org.example.traveljavaserver.vo.TravelRecommendVO;

public final class AgentTypes {
  private AgentTypes() {}
  public record Start(
      @NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}") String requestId,
      @NotBlank @Size(max = 50) String city,
      @Min(1) @Max(7) int days,
      @NotNull @DecimalMin("100") @DecimalMax("1000000") @Digits(integer = 10, fraction = 2) BigDecimal budget,
      @Size(max = 1000) String instruction,
      @Pattern(regexp = "walking|transit|driving") String travelMode) {
    public Start { travelMode = TravelModes.normalize(travelMode); }
    public Start(String requestId, String city, int days, BigDecimal budget, String instruction) {
      this(requestId, city, days, budget, instruction, "walking");
    }
  }
  public record Revise(
      @NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}") String requestId,
      @Min(1) int baseVersion, @Min(1) @Max(30) int day,
      @NotBlank @Pattern(regexp = "day|morning|afternoon|evening") String slot,
      @NotBlank @Size(max = 1000) String instruction) {}
  public record Save(@Size(max = 80) String title) {}
  public record Report(boolean valid, List<String> issues, List<String> warnings,
      BigDecimal estimatedTotal, BigDecimal remainingBudget) {}
  public record Evidence(String status, String budgetScope, Report validation,
      List<PlanTypes.Trace> traces, List<ModelTrace.Entry> modelCalls) {}
  public record MinimumExpenses(String policy, BigDecimal accommodation, BigDecimal food,
      BigDecimal other, BigDecimal total, BigDecimal requestedBudget) {}
  public record Refusal(String code, String reason, MinimumExpenses minimumExpenses) {
    public Refusal(String code, String reason) { this(code, reason, null); }
  }
  public record View(String id, String status, String progress, String errorCode, String message,
      String tripId, Integer baseVersion, TravelRecommendVO plan,
      List<PlanTypes.Change> changes, List<PlanTypes.Trace> traces, Integer savedVersion,
      Report validation, int modelRounds, int toolCalls, String travelMode, Refusal refusal) {}
}


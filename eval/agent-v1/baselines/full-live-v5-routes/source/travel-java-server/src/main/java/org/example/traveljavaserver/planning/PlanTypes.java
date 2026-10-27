package org.example.traveljavaserver.planning;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.example.traveljavaserver.vo.TravelRecommendVO;

public final class PlanTypes {
  private PlanTypes() {}

  public record Place(
      String id,
      String name,
      double latitude,
      double longitude,
      boolean indoor,
      String category,
      String sourceUrl,
      String queriedAt) {}

  public record Places(
      String city, List<Place> items, String source, String queriedAt, boolean cached) {}

  public record Leg(
      String fromId,
      String toId,
      double meters,
      double seconds,
      String profile,
      String source,
      String queriedAt,
      BigDecimal estimatedFare) {}

  public record Routes(List<Leg> legs, String source, String queriedAt) {}

  public record Trace(String tool, String status, long durationMs, String detail, String at) {}

  public record Evidence(
      String status,
      String attribution,
      String priceNotice,
      List<Trace> traces,
      List<org.example.traveljavaserver.utils.ModelTrace.Entry> modelCalls) {}

  public record Change(String path, String label, String before, String after) {}

  public record Start(
      @NotBlank String city,
      @Min(1) @Max(3) int days,
      @NotNull @DecimalMin("100") @DecimalMax("1000000") @Digits(integer = 10, fraction = 2)
          BigDecimal budget) {}

  public record Revise(
      @Min(1) int baseVersion,
      @NotBlank @Pattern(regexp = "indoor|relaxed|budget|refresh") String action,
      @Min(1) @Max(3) Integer day,
      @DecimalMin("100") @DecimalMax("1000000") @Digits(integer = 10, fraction = 2)
          BigDecimal budget,
      @Size(max = 500) String instruction) {}

  public record Save(@Size(max = 80) String title) {}

  public record View(
      String id,
      String status,
      String progress,
      String errorCode,
      String message,
      String tripId,
      Integer baseVersion,
      TravelRecommendVO plan,
      List<Change> changes,
      List<Trace> traces,
      Integer savedVersion) {}
}

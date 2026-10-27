package org.example.traveljavaserver.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import org.example.traveljavaserver.vo.TravelRecommendVO;

public final class DataRequests {
  private DataRequests() {}

  public record SaveTrip(
      @Size(max = 80) String title,
      @NotNull @Valid TravelRecommendVO plan,
      @Min(1) Integer baseVersion) {}

  public record NewConversation(
      @Pattern(regexp = "[0-9a-fA-F-]{36}") String tripId,
      @Size(max = 80) String title,
      @Size(max = 50) String city,
      @Min(1) @Max(30) Integer days,
      @DecimalMin("100") @DecimalMax("1000000") @Digits(integer = 10, fraction = 2)
          BigDecimal budget) {}
}

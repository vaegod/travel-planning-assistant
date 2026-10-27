package org.example.traveljavaserver.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class TravelRequestDTO {
  @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
  private String requestId;
  @NotBlank(message = "城市不能为空")
  @Size(max = 50, message = "城市不能超过50个字符")
  private String city;

  @NotNull(message = "天数不能为空")
  @Min(value = 1, message = "天数不能小于1")
  @Max(value = 30, message = "天数不能大于30")
  private Integer days;

  @NotNull(message = "预算不能为空")
  @DecimalMin(value = "100", message = "预算不能小于100")
  @DecimalMax(value = "1000000", message = "预算不能超过1000000")
  @Digits(integer = 10, fraction = 2, message = "预算最多保留两位小数")
  private java.math.BigDecimal budget;
}

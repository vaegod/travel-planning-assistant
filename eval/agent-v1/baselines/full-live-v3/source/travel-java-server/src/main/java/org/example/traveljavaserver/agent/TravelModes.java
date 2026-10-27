package org.example.traveljavaserver.agent;

import java.util.List;

public final class TravelModes {
  public static final List<String> ALL = List.of("walking", "transit", "driving");
  private TravelModes() {}
  public static String normalize(String value) { return value == null ? "walking" : value; }
  public static String label(String value) {
    return switch (normalize(value)) {
      case "walking" -> "步行";
      case "transit" -> "公交（含地铁）";
      case "driving" -> "自驾";
      default -> throw new IllegalArgumentException("不支持的出行方式");
    };
  }
}

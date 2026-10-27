package org.example.traveljavaserver.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.core.io.ClassPathResource;

/** Generated from the same versioned destination catalog as the home page. */
public final class CityCatalog {
  private static final Map<String, Set<String>> ALIASES = new LinkedHashMap<>();
  private static final Map<String, String> CANONICAL = new LinkedHashMap<>();
  static {
    try (var input = new ClassPathResource("knowledge/destinations.json").getInputStream()) {
      for (var item : new ObjectMapper().readTree(input)) {
        String name = item.path("name").asText(), full = item.path("fullName").asText();
        var names = new LinkedHashSet<>(List.of(full, name, name.replaceFirst("市$", "")));
        ALIASES.put(full, names);
        for (String alias : names) CANONICAL.putIfAbsent(alias, full);
      }
    } catch (Exception e) { throw new ExceptionInInitializerError(e); }
  }
  private CityCatalog() {}
  public static String canonical(String city) {
    String value = city == null ? "" : city.strip();
    return CANONICAL.getOrDefault(value, value);
  }
  public static List<String> aliases(String city) {
    String value = canonical(city);
    return List.copyOf(ALIASES.getOrDefault(value, Set.of(value)));
  }
  public static List<String> mentioned(String text) {
    // Prefer the longest name at each position, so a full provincial name cannot match twice.
    var names = CANONICAL.keySet().stream().filter(n -> n.length() >= 2)
        .sorted(Comparator.comparingInt(String::length).reversed()).toList();
    Set<String> found = new LinkedHashSet<>();
    for (int i = 0; i < text.length(); i++) {
      for (String name : names) if (text.startsWith(name, i)) {
        int end = i + name.length();
        if (!name.endsWith("市") && end < text.length() && "区县路街".indexOf(text.charAt(end)) >= 0) continue;
        found.add(CANONICAL.get(name)); i += name.length() - 1; break;
      }
    }
    return List.copyOf(found);
  }
}

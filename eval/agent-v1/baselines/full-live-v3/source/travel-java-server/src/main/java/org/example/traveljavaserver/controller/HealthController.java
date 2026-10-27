package org.example.traveljavaserver.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
  private final JdbcTemplate jdbc;

  public HealthController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/api/health")
  public ResponseEntity<Map<String, String>> health() {
    try {
      jdbc.queryForObject("SELECT 1", Integer.class);
      return ResponseEntity.ok(Map.of("status", "UP"));
    } catch (RuntimeException e) {
      return ResponseEntity.status(503).body(Map.of("status", "DOWN"));
    }
  }
}

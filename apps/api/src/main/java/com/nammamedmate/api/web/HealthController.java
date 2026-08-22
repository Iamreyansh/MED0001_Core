package com.nammamedmate.api.web;

import com.nammamedmate.kernel.api.ApiResponse;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

  private final JdbcTemplate jdbc;

  public HealthController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/health")
  public ResponseEntity<ApiResponse<Map<String, String>>> health() {
    try {
      jdbc.queryForObject("SELECT 1", Integer.class);
      return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "UP")));
    } catch (RuntimeException ex) {
      return ResponseEntity.status(503).body(ApiResponse.ok(Map.of("status", "DOWN")));
    }
  }
}

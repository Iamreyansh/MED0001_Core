package com.nammamedmate.api.web;

import com.nammamedmate.kernel.api.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

  private final JdbcTemplate jdbc;
  private final ObjectProvider<RedisConnectionFactory> redis;

  public HealthController(JdbcTemplate jdbc) {
    this(jdbc, null);
  }

  @Autowired
  public HealthController(JdbcTemplate jdbc, ObjectProvider<RedisConnectionFactory> redis) {
    this.jdbc = jdbc;
    this.redis = redis;
  }

  @GetMapping("/health")
  public ResponseEntity<ApiResponse<Map<String, String>>> health() {
    Map<String, String> data = new LinkedHashMap<>();
    try {
      jdbc.queryForObject("SELECT 1", Integer.class);
      data.put("db", "UP");
    } catch (RuntimeException ex) {
      data.put("status", "DOWN");
      data.put("db", "DOWN");
      return ResponseEntity.status(503).body(ApiResponse.ok(data));
    }
    boolean degraded = false;
    try {
      Integer stale =
          jdbc.queryForObject(
              """
              SELECT COUNT(*) FROM outbox_message
               WHERE published = FALSE AND poisoned = FALSE
                 AND created_at < NOW() - INTERVAL '15 minutes'
              """,
              Integer.class);
      if (stale != null && stale > 0) {
        data.put("outbox", "DEGRADED");
        degraded = true;
      } else {
        data.put("outbox", "UP");
      }
    } catch (RuntimeException ex) {
      data.put("outbox", "DEGRADED");
      degraded = true;
    }
    if (redis != null && redis.getIfAvailable() != null) {
      try (var connection = redis.getIfAvailable().getConnection()) {
        connection.ping();
        data.put("redis", "UP");
      } catch (RuntimeException ex) {
        data.put("redis", "DEGRADED");
        degraded = true;
      }
    }
    data.put("status", degraded ? "DEGRADED" : "UP");
    return ResponseEntity.ok(ApiResponse.ok(data));
  }
}

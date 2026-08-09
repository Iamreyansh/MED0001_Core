package com.nammamedmate.integration.adapter.out.persistence;

import com.nammamedmate.integration.application.port.out.MapsApiCallLogStore;
import com.nammamedmate.integration.domain.MapsApiCallLog;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcMapsApiCallLogStore implements MapsApiCallLogStore {

  private final JdbcTemplate jdbc;

  public JdbcMapsApiCallLogStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(MapsApiCallLog log) {
    jdbc.update(
        """
        INSERT INTO maps_api_call_log (
          id, api_type, request_summary, response_status, latency_ms,
          was_cache_hit, estimated_cost_rs, called_at, calling_service
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        log.id(),
        log.apiType(),
        log.requestSummary(),
        log.responseStatus(),
        log.latencyMs(),
        log.wasCacheHit(),
        log.estimatedCostRs(),
        Timestamp.from(log.calledAt()),
        log.callingService());
  }

  @Override
  public BigDecimal sumEstimatedCostSince(Instant since) {
    BigDecimal sum =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(estimated_cost_rs), 0) FROM maps_api_call_log WHERE called_at >= ?",
            BigDecimal.class,
            Timestamp.from(since));
    return sum == null ? BigDecimal.ZERO : sum;
  }
}

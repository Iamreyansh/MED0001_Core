package com.nammamedmate.integration.adapter.out.persistence;

import com.nammamedmate.integration.application.port.out.GovernmentApiCallLogStore;
import com.nammamedmate.integration.domain.GovernmentApiCallLog;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcGovernmentApiCallLogStore implements GovernmentApiCallLogStore {

  private final JdbcTemplate jdbc;

  public JdbcGovernmentApiCallLogStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(GovernmentApiCallLog log) {
    jdbc.update(
        """
        INSERT INTO government_api_call_log (
          id, api_type, identifier, http_status, result_status, latency_ms,
          was_cache_hit, entity_type, entity_id, called_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        log.id(),
        log.apiType(),
        log.identifier(),
        log.httpStatus(),
        log.resultStatus(),
        log.latencyMs(),
        log.wasCacheHit(),
        log.entityType(),
        log.entityId(),
        Timestamp.from(log.calledAt()));
  }
}

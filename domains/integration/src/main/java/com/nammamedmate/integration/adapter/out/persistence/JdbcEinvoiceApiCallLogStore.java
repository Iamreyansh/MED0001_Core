package com.nammamedmate.integration.adapter.out.persistence;

import com.nammamedmate.integration.application.port.out.EinvoiceApiCallLogStore;
import com.nammamedmate.integration.domain.EinvoiceApiCallLog;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcEinvoiceApiCallLogStore implements EinvoiceApiCallLogStore {

  private final JdbcTemplate jdbc;

  public JdbcEinvoiceApiCallLogStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(EinvoiceApiCallLog log) {
    jdbc.update(
        """
        INSERT INTO einvoice_api_call_log (
          id, api_type, request_summary, http_status, response_status, latency_ms, called_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        log.id(),
        log.apiType(),
        log.requestSummary(),
        log.httpStatus(),
        log.responseStatus(),
        log.latencyMs(),
        Timestamp.from(log.calledAt()));
  }
}

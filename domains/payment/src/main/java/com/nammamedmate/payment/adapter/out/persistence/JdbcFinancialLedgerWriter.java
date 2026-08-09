package com.nammamedmate.payment.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.payment.application.port.out.FinancialLedgerWriterPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFinancialLedgerWriter implements FinancialLedgerWriterPort {

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcFinancialLedgerWriter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void append(
      String entryType,
      UUID referenceId,
      String referenceType,
      long creditPaise,
      long debitPaise,
      String description,
      Map<String, Object> metadata) {
    if (creditPaise <= 0 && debitPaise <= 0) {
      return;
    }
    if (creditPaise > 0 && debitPaise > 0) {
      throw new IllegalArgumentException("exactly one of credit/debit must be > 0");
    }
    String metaJson;
    try {
      if (metadata == null) {
        metaJson = null;
      } else if (metadata.isEmpty()) {
        metaJson = null;
      } else {
        metaJson = objectMapper.writeValueAsString(metadata);
      }
    } catch (Exception e) {
      throw new IllegalStateException("json encode failed", e);
    }
    jdbc.update(
        """
        INSERT INTO financial_ledger (
          id, entry_type, reference_id, reference_type, credit_paise, debit_paise,
          description, metadata, created_at)
        VALUES (?,?,?,?,?,?,?,?::jsonb,?)
        """,
        Ids.newId(),
        entryType,
        referenceId,
        referenceType,
        Math.max(0L, creditPaise),
        Math.max(0L, debitPaise),
        description,
        metaJson,
        Timestamp.from(Instant.now()));
  }
}

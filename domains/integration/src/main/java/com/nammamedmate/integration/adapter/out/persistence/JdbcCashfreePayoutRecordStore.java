package com.nammamedmate.integration.adapter.out.persistence;

import com.nammamedmate.integration.application.port.out.CashfreePayoutRecordStore;
import com.nammamedmate.integration.domain.CashfreePayoutRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcCashfreePayoutRecordStore implements CashfreePayoutRecordStore {

  private final JdbcTemplate jdbc;

  public JdbcCashfreePayoutRecordStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<CashfreePayoutRecord> MAPPER =
      (rs, i) ->
          new CashfreePayoutRecord(
              (UUID) rs.getObject("id"),
              rs.getString("entity_type"),
              (UUID) rs.getObject("entity_id"),
              rs.getString("beneficiary_id"),
              rs.getString("cashfree_transfer_id"),
              rs.getString("reference_id"),
              rs.getLong("amount_paise"),
              rs.getString("mode"),
              rs.getString("status"),
              rs.getInt("retry_count"),
              instant(rs.getTimestamp("initiated_at")),
              instant(rs.getTimestamp("processed_at")),
              rs.getString("failure_reason"));

  @Override
  public void insert(CashfreePayoutRecord record) {
    jdbc.update(
        """
        INSERT INTO cashfree_payout_records (
          id, entity_type, entity_id, beneficiary_id, cashfree_transfer_id,
          reference_id, amount_paise, mode, status, retry_count,
          initiated_at, processed_at, failure_reason
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        record.id(),
        record.entityType(),
        record.entityId(),
        record.beneficiaryId(),
        record.cashfreeTransferId(),
        record.referenceId(),
        record.amountPaise(),
        record.mode(),
        record.status(),
        record.retryCount(),
        Timestamp.from(record.initiatedAt()),
        record.processedAt() == null ? null : Timestamp.from(record.processedAt()),
        record.failureReason());
  }

  @Override
  public void update(CashfreePayoutRecord record) {
    jdbc.update(
        """
        UPDATE cashfree_payout_records SET
          cashfree_transfer_id = ?, status = ?, retry_count = ?,
          processed_at = ?, failure_reason = ?
        WHERE id = ?
        """,
        record.cashfreeTransferId(),
        record.status(),
        record.retryCount(),
        record.processedAt() == null ? null : Timestamp.from(record.processedAt()),
        record.failureReason(),
        record.id());
  }

  @Override
  public Optional<CashfreePayoutRecord> findById(UUID id) {
    return one("SELECT * FROM cashfree_payout_records WHERE id = ?", id);
  }

  @Override
  public Optional<CashfreePayoutRecord> findByCashfreexPayoutId(String payoutId) {
    return one("SELECT * FROM cashfree_payout_records WHERE cashfree_transfer_id = ?", payoutId);
  }

  @Override
  public Optional<CashfreePayoutRecord> findByReferenceId(String referenceId) {
    return one("SELECT * FROM cashfree_payout_records WHERE reference_id = ?", referenceId);
  }

  @Override
  public List<CashfreePayoutRecord> findRetryEligible(Instant initiatedBefore, int limit) {
    return jdbc.query(
        """
        SELECT * FROM cashfree_payout_records
        WHERE status = 'failed' AND retry_count = 0 AND initiated_at <= ?
        ORDER BY initiated_at ASC
        LIMIT ?
        """,
        MAPPER,
        Timestamp.from(initiatedBefore),
        limit);
  }

  private Optional<CashfreePayoutRecord> one(String sql, Object arg) {
    List<CashfreePayoutRecord> rows = jdbc.query(sql, MAPPER, arg);
    return rows.stream().findFirst();
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}

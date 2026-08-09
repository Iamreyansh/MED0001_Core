package com.nammamedmate.integration.adapter.out.persistence;

import com.nammamedmate.integration.application.port.out.RazorpayXPayoutRecordStore;
import com.nammamedmate.integration.domain.RazorpayXPayoutRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcRazorpayXPayoutRecordStore implements RazorpayXPayoutRecordStore {

  private final JdbcTemplate jdbc;

  public JdbcRazorpayXPayoutRecordStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<RazorpayXPayoutRecord> MAPPER =
      (rs, i) ->
          new RazorpayXPayoutRecord(
              (UUID) rs.getObject("id"),
              rs.getString("entity_type"),
              (UUID) rs.getObject("entity_id"),
              rs.getString("fund_account_id"),
              rs.getString("razorpayx_payout_id"),
              rs.getString("reference_id"),
              rs.getLong("amount_paise"),
              rs.getString("mode"),
              rs.getString("status"),
              rs.getInt("retry_count"),
              instant(rs.getTimestamp("initiated_at")),
              instant(rs.getTimestamp("processed_at")),
              rs.getString("failure_reason"));

  @Override
  public void insert(RazorpayXPayoutRecord record) {
    jdbc.update(
        """
        INSERT INTO razorpayx_payout_records (
          id, entity_type, entity_id, fund_account_id, razorpayx_payout_id,
          reference_id, amount_paise, mode, status, retry_count,
          initiated_at, processed_at, failure_reason
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        record.id(),
        record.entityType(),
        record.entityId(),
        record.fundAccountId(),
        record.razorpayxPayoutId(),
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
  public void update(RazorpayXPayoutRecord record) {
    jdbc.update(
        """
        UPDATE razorpayx_payout_records SET
          razorpayx_payout_id = ?, status = ?, retry_count = ?,
          processed_at = ?, failure_reason = ?
        WHERE id = ?
        """,
        record.razorpayxPayoutId(),
        record.status(),
        record.retryCount(),
        record.processedAt() == null ? null : Timestamp.from(record.processedAt()),
        record.failureReason(),
        record.id());
  }

  @Override
  public Optional<RazorpayXPayoutRecord> findById(UUID id) {
    return one("SELECT * FROM razorpayx_payout_records WHERE id = ?", id);
  }

  @Override
  public Optional<RazorpayXPayoutRecord> findByRazorpayxPayoutId(String payoutId) {
    return one("SELECT * FROM razorpayx_payout_records WHERE razorpayx_payout_id = ?", payoutId);
  }

  @Override
  public Optional<RazorpayXPayoutRecord> findByReferenceId(String referenceId) {
    return one("SELECT * FROM razorpayx_payout_records WHERE reference_id = ?", referenceId);
  }

  @Override
  public List<RazorpayXPayoutRecord> findRetryEligible(Instant initiatedBefore, int limit) {
    return jdbc.query(
        """
        SELECT * FROM razorpayx_payout_records
        WHERE status = 'failed' AND retry_count = 0 AND initiated_at <= ?
        ORDER BY initiated_at ASC
        LIMIT ?
        """,
        MAPPER,
        Timestamp.from(initiatedBefore),
        limit);
  }

  private Optional<RazorpayXPayoutRecord> one(String sql, Object arg) {
    List<RazorpayXPayoutRecord> rows = jdbc.query(sql, MAPPER, arg);
    return rows.stream().findFirst();
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}

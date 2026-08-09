package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.SettlementStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcSettlementStore implements SettlementStore {

  private final JdbcTemplate jdbc;

  public JdbcSettlementStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<SettlementRow> findById(UUID settlementId) {
    return queryOne("SELECT * FROM settlement WHERE id = ? AND deleted_at IS NULL", settlementId);
  }

  @Override
  public Optional<SettlementRow> findByIdForPharmacy(UUID pharmacyId, UUID settlementId) {
    return queryOne(
        """
        SELECT * FROM settlement
        WHERE id = ? AND pharmacy_id = ? AND deleted_at IS NULL
        """,
        settlementId,
        pharmacyId);
  }

  @Override
  public Optional<SettlementRow> findByIdempotencyKey(String idempotencyKey) {
    return queryOne(
        """
        SELECT * FROM settlement
        WHERE release_idempotency_key = ? AND deleted_at IS NULL
        """,
        idempotencyKey);
  }

  @Override
  public Optional<SettlementRow> findByRazorpayxPayoutId(String razorpayxPayoutId) {
    return queryOne(
        """
        SELECT * FROM settlement
        WHERE razorpayx_payout_id = ? AND deleted_at IS NULL
        """,
        razorpayxPayoutId);
  }

  @Override
  public Optional<SettlementRow> findForPeriod(
      UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd) {
    return queryOne(
        """
        SELECT * FROM settlement
        WHERE pharmacy_id = ? AND period_start = ? AND period_end = ?
          AND deleted_at IS NULL
        """,
        pharmacyId,
        periodStart,
        periodEnd);
  }

  @Override
  public Optional<SettlementRow> findLatestPaid(UUID pharmacyId) {
    List<SettlementRow> rows =
        jdbc.query(
            """
            SELECT * FROM settlement
            WHERE pharmacy_id = ? AND status = 'PAID' AND deleted_at IS NULL
            ORDER BY paid_at DESC NULLS LAST
            LIMIT 1
            """,
            this::mapRow,
            pharmacyId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public void insert(SettlementRow row) {
    jdbc.update(
        """
        INSERT INTO settlement (
          id, pharmacy_id, period_start, period_end, gmv_paise, commission_pct,
          commission_earned_paise, tcs_rate_pct, tcs_deducted_paise, net_paid_paise,
          status, hold_reason, released_by, released_at, paid_at, razorpayx_payout_id,
          utr_number, receipt_url, release_idempotency_key, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        row.id(),
        row.pharmacyId(),
        row.periodStart(),
        row.periodEnd(),
        row.gmvPaise(),
        row.commissionPct(),
        row.commissionEarnedPaise(),
        row.tcsRatePct(),
        row.tcsDeductedPaise(),
        row.netPaidPaise(),
        row.status(),
        row.holdReason(),
        row.releasedBy(),
        ts(row.releasedAt()),
        ts(row.paidAt()),
        row.razorpayxPayoutId(),
        row.utrNumber(),
        row.receiptUrl(),
        row.releaseIdempotencyKey(),
        Timestamp.from(row.createdAt()),
        Timestamp.from(row.updatedAt()));
  }

  @Override
  public void updateReleased(
      UUID settlementId,
      String status,
      UUID releasedBy,
      Instant releasedAt,
      String razorpayxPayoutId,
      String idempotencyKey,
      Instant updatedAt) {
    jdbc.update(
        """
        UPDATE settlement SET
          status = ?,
          released_by = ?,
          released_at = ?,
          razorpayx_payout_id = ?,
          release_idempotency_key = ?,
          updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        status,
        releasedBy,
        Timestamp.from(releasedAt),
        razorpayxPayoutId,
        idempotencyKey,
        Timestamp.from(updatedAt),
        settlementId);
  }

  @Override
  public boolean claimForRelease(
      UUID settlementId, UUID pharmacyId, String idempotencyKey, Instant updatedAt) {
    int updated =
        jdbc.update(
            """
            UPDATE settlement SET
              release_idempotency_key = ?,
              updated_at = ?
            WHERE id = ? AND pharmacy_id = ? AND status = 'PENDING_RELEASE'
              AND release_idempotency_key IS NULL AND deleted_at IS NULL
            """,
            idempotencyKey,
            Timestamp.from(updatedAt),
            settlementId,
            pharmacyId);
    return updated > 0;
  }

  @Override
  public boolean finalizeRelease(
      UUID settlementId,
      UUID releasedBy,
      Instant releasedAt,
      String razorpayxPayoutId,
      String idempotencyKey,
      Instant updatedAt) {
    int updated =
        jdbc.update(
            """
            UPDATE settlement SET
              status = 'RELEASED',
              released_by = ?,
              released_at = ?,
              razorpayx_payout_id = ?,
              updated_at = ?
            WHERE id = ? AND status = 'PENDING_RELEASE'
              AND release_idempotency_key = ? AND deleted_at IS NULL
            """,
            releasedBy,
            Timestamp.from(releasedAt),
            razorpayxPayoutId,
            Timestamp.from(updatedAt),
            settlementId,
            idempotencyKey);
    return updated > 0;
  }

  @Override
  public boolean markReleaseFailed(UUID settlementId, String idempotencyKey, Instant updatedAt) {
    int updated =
        jdbc.update(
            """
            UPDATE settlement SET
              status = 'FAILED',
              updated_at = ?
            WHERE id = ? AND status = 'PENDING_RELEASE'
              AND release_idempotency_key = ? AND deleted_at IS NULL
            """,
            Timestamp.from(updatedAt),
            settlementId,
            idempotencyKey);
    return updated > 0;
  }

  @Override
  public void updateHeld(UUID settlementId, String reason, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE settlement SET status = 'HELD', hold_reason = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        reason,
        Timestamp.from(updatedAt),
        settlementId);
  }

  @Override
  public void updatePaid(
      UUID settlementId, String utrNumber, String receiptUrl, Instant paidAt, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE settlement SET
          status = 'PAID',
          utr_number = ?,
          receipt_url = ?,
          paid_at = ?,
          updated_at = ?
        WHERE id = ? AND status = 'RELEASED' AND deleted_at IS NULL
        """,
        utrNumber,
        receiptUrl,
        Timestamp.from(paidAt),
        Timestamp.from(updatedAt),
        settlementId);
  }

  @Override
  public ListResult list(UUID pharmacyId, ListFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE pharmacy_id = ? AND deleted_at IS NULL ");
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);

    if (filter.status() != null && !"ALL".equalsIgnoreCase(filter.status())) {
      where.append(" AND status = ? ");
      args.add(filter.status());
    }
    if (filter.fromDate() != null) {
      where.append(" AND period_start >= ? ");
      args.add(filter.fromDate());
    }
    if (filter.toDate() != null) {
      where.append(" AND period_end <= ? ");
      args.add(filter.toDate());
    }

    Long total =
        jdbc.queryForObject("SELECT COUNT(*) FROM settlement" + where, Long.class, args.toArray());
    long count = total == null ? 0L : total;

    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(filter.offset());

    List<SettlementRow> rows =
        jdbc.query(
            """
            SELECT * FROM settlement
            """
                + where
                + """
             ORDER BY period_start DESC
             LIMIT ? OFFSET ?
            """,
            this::mapRow,
            pageArgs.toArray());
    return new ListResult(rows, count);
  }

  @Override
  public boolean existsForPeriod(UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM settlement
            WHERE pharmacy_id = ? AND period_start = ? AND period_end = ?
              AND deleted_at IS NULL
            """,
            Integer.class,
            pharmacyId,
            periodStart,
            periodEnd);
    return count != null && count > 0;
  }

  @Override
  public long sumUnconsumedCarryForwardPaise(UUID pharmacyId) {
    Long sum =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(net_paid_paise), 0) FROM settlement
            WHERE pharmacy_id = ?
              AND status = 'BELOW_THRESHOLD_CARRIED'
              AND carry_forward_consumed_at IS NULL
              AND deleted_at IS NULL
            """,
            Long.class,
            pharmacyId);
    return sum == null ? 0L : sum;
  }

  @Override
  public void markCarryForwardConsumed(UUID pharmacyId, Instant consumedAt) {
    jdbc.update(
        """
        UPDATE settlement SET
          carry_forward_consumed_at = ?,
          updated_at = ?
        WHERE pharmacy_id = ?
          AND status = 'BELOW_THRESHOLD_CARRIED'
          AND carry_forward_consumed_at IS NULL
          AND deleted_at IS NULL
        """,
        Timestamp.from(consumedAt),
        Timestamp.from(consumedAt),
        pharmacyId);
  }

  private Optional<SettlementRow> queryOne(String sql, Object... args) {
    List<SettlementRow> rows = jdbc.query(sql, this::mapRow, args);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  private SettlementRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new SettlementRow(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getObject("period_start", LocalDate.class),
        rs.getObject("period_end", LocalDate.class),
        rs.getLong("gmv_paise"),
        rs.getBigDecimal("commission_pct"),
        rs.getLong("commission_earned_paise"),
        rs.getBigDecimal("tcs_rate_pct"),
        rs.getLong("tcs_deducted_paise"),
        rs.getLong("net_paid_paise"),
        rs.getString("status"),
        rs.getString("hold_reason"),
        (UUID) rs.getObject("released_by"),
        tsInstant(rs, "released_at"),
        tsInstant(rs, "paid_at"),
        rs.getString("razorpayx_payout_id"),
        rs.getString("utr_number"),
        rs.getString("receipt_url"),
        rs.getString("release_idempotency_key"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant tsInstant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}

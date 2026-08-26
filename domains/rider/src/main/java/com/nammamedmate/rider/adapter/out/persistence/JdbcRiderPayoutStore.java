package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.RiderPayoutStore;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRiderPayoutStore implements RiderPayoutStore {

  private final JdbcTemplate jdbc;

  public JdbcRiderPayoutStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(PayoutRecord row) {
    jdbc.update(
        """
        INSERT INTO rider_payouts (
          id, rider_id, cycle_from, cycle_to, base_earnings_paise, incentives_paise,
          tips_paise, streak_bonus_paise, carry_forward_paise, cod_deducted_paise,
          net_payout_paise, status, hold_reason, cashfree_transfer_id, payout_reference,
          release_notes, released_by, released_at, retry_count, next_retry_at,
          last_attempt_at, created_at, updated_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        row.id(),
        row.riderId(),
        Date.valueOf(row.cycleFrom()),
        Date.valueOf(row.cycleTo()),
        row.baseEarningsPaise(),
        row.incentivesPaise(),
        row.tipsPaise(),
        row.streakBonusPaise(),
        row.carryForwardPaise(),
        row.codDeductedPaise(),
        row.netPayoutPaise(),
        row.status(),
        row.holdReason(),
        row.cashfreeTransferId(),
        row.payoutReference(),
        row.releaseNotes(),
        row.releasedBy(),
        ts(row.releasedAt()),
        row.retryCount(),
        ts(row.nextRetryAt()),
        ts(row.lastAttemptAt()),
        ts(row.createdAt()),
        ts(row.updatedAt()));
  }

  @Override
  public void update(PayoutRecord row) {
    jdbc.update(
        """
        UPDATE rider_payouts SET
          base_earnings_paise = ?, incentives_paise = ?, tips_paise = ?,
          streak_bonus_paise = ?, carry_forward_paise = ?, cod_deducted_paise = ?,
          net_payout_paise = ?, status = ?, hold_reason = ?, cashfree_transfer_id = ?,
          payout_reference = ?, release_notes = ?, released_by = ?, released_at = ?,
          retry_count = ?, next_retry_at = ?, last_attempt_at = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        row.baseEarningsPaise(),
        row.incentivesPaise(),
        row.tipsPaise(),
        row.streakBonusPaise(),
        row.carryForwardPaise(),
        row.codDeductedPaise(),
        row.netPayoutPaise(),
        row.status(),
        row.holdReason(),
        row.cashfreeTransferId(),
        row.payoutReference(),
        row.releaseNotes(),
        row.releasedBy(),
        ts(row.releasedAt()),
        row.retryCount(),
        ts(row.nextRetryAt()),
        ts(row.lastAttemptAt()),
        ts(row.updatedAt()),
        row.id());
  }

  @Override
  public Optional<PayoutRecord> findById(UUID id) {
    List<PayoutRecord> rows =
        jdbc.query(
            "SELECT * FROM rider_payouts WHERE id = ? AND deleted_at IS NULL", this::map, id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<PayoutRecord> findByIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return Optional.empty();
    }
    List<PayoutRecord> rows =
        jdbc.query(
            """
            SELECT * FROM rider_payouts
            WHERE release_idempotency_key = ? AND deleted_at IS NULL
            """,
            this::map,
            idempotencyKey);
    return rows.stream().findFirst();
  }

  @Override
  public boolean claimForRelease(
      UUID payoutId, UUID riderId, String idempotencyKey, Instant updatedAt) {
    int n =
        jdbc.update(
            """
            UPDATE rider_payouts SET
              release_idempotency_key = ?,
              updated_at = ?
            WHERE id = ? AND rider_id = ? AND deleted_at IS NULL
              AND release_idempotency_key IS NULL
              AND status IN ('HELD', 'FAILED', 'PENDING')
            """,
            idempotencyKey,
            ts(updatedAt),
            payoutId,
            riderId);
    return n == 1;
  }

  @Override
  public Optional<PayoutRecord> findByRiderAndCycle(
      UUID riderId, LocalDate cycleFrom, LocalDate cycleTo) {
    List<PayoutRecord> rows =
        jdbc.query(
            """
            SELECT * FROM rider_payouts
            WHERE rider_id = ? AND cycle_from = ? AND cycle_to = ? AND deleted_at IS NULL
            """,
            this::map,
            riderId,
            Date.valueOf(cycleFrom),
            Date.valueOf(cycleTo));
    return rows.stream().findFirst();
  }

  @Override
  public List<PayoutRecord> listForRider(
      UUID riderId, LocalDate from, LocalDate to, int offset, int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT * FROM rider_payouts WHERE rider_id = ? AND deleted_at IS NULL");
    if (from != null) {
      sql.append(" AND cycle_from >= ? ");
    }
    if (to != null) {
      sql.append(" AND cycle_to <= ? ");
    }
    sql.append(" ORDER BY cycle_from DESC LIMIT ? OFFSET ? ");
    return jdbc.query(sql.toString(), this::map, listArgs(riderId, from, to, limit, offset));
  }

  @Override
  public long countForRider(UUID riderId, LocalDate from, LocalDate to) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT COUNT(1) FROM rider_payouts WHERE rider_id = ? AND deleted_at IS NULL");
    if (from != null) {
      sql.append(" AND cycle_from >= ? ");
    }
    if (to != null) {
      sql.append(" AND cycle_to <= ? ");
    }
    Long n = jdbc.queryForObject(sql.toString(), Long.class, countArgs(riderId, from, to));
    return n == null ? 0L : n;
  }

  @Override
  public List<PayoutRecord> findDueForRetry(Instant now, int limit) {
    return jdbc.query(
        """
        SELECT * FROM rider_payouts
        WHERE deleted_at IS NULL
          AND status = 'PENDING'
          AND retry_count = 0
          AND next_retry_at IS NOT NULL
          AND next_retry_at <= ?
        ORDER BY next_retry_at ASC
        LIMIT ?
        """,
        this::map,
        ts(now),
        limit);
  }

  private PayoutRecord map(ResultSet rs, int rowNum) throws SQLException {
    return new PayoutRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("rider_id"),
        rs.getDate("cycle_from").toLocalDate(),
        rs.getDate("cycle_to").toLocalDate(),
        rs.getLong("base_earnings_paise"),
        rs.getLong("incentives_paise"),
        rs.getLong("tips_paise"),
        rs.getLong("streak_bonus_paise"),
        rs.getLong("carry_forward_paise"),
        rs.getLong("cod_deducted_paise"),
        rs.getLong("net_payout_paise"),
        rs.getString("status"),
        rs.getString("hold_reason"),
        rs.getString("cashfree_transfer_id"),
        rs.getString("payout_reference"),
        rs.getString("release_notes"),
        (UUID) rs.getObject("released_by"),
        instant(rs.getTimestamp("released_at")),
        rs.getInt("retry_count"),
        instant(rs.getTimestamp("next_retry_at")),
        instant(rs.getTimestamp("last_attempt_at")),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")));
  }

  private static Object[] listArgs(
      UUID riderId, LocalDate from, LocalDate to, int limit, int offset) {
    if (from != null && to != null) {
      return new Object[] {riderId, Date.valueOf(from), Date.valueOf(to), limit, offset};
    }
    if (from != null) {
      return new Object[] {riderId, Date.valueOf(from), limit, offset};
    }
    if (to != null) {
      return new Object[] {riderId, Date.valueOf(to), limit, offset};
    }
    return new Object[] {riderId, limit, offset};
  }

  private static Object[] countArgs(UUID riderId, LocalDate from, LocalDate to) {
    if (from != null && to != null) {
      return new Object[] {riderId, Date.valueOf(from), Date.valueOf(to)};
    }
    if (from != null) {
      return new Object[] {riderId, Date.valueOf(from)};
    }
    if (to != null) {
      return new Object[] {riderId, Date.valueOf(to)};
    }
    return new Object[] {riderId};
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}

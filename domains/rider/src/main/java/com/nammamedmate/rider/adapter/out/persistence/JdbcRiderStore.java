package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.RiderStore;
import java.math.BigDecimal;
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
public class JdbcRiderStore implements RiderStore {

  private final JdbcTemplate jdbc;

  public JdbcRiderStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(RiderRecord rider) {
    jdbc.update(
        """
        INSERT INTO riders (
          id, name, phone, email, vehicle_type, vehicle_plate_number, primary_zone_id,
          status, kyc_status, kyc_submitted_at, kyc_reviewed_at, kyc_reviewed_by,
          kyc_rejection_reason, kyc_rejection_notes, aadhaar_verified, avg_rating,
          total_trips, on_time_pct, earnings_wallet_balance_paise, cod_in_hand_paise,
          daily_streak_days, blocked_reason, blocked_by, blocked_at, created_at, updated_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        rider.id(),
        rider.name(),
        rider.phone(),
        rider.email(),
        rider.vehicleType(),
        rider.vehiclePlateNumber(),
        rider.primaryZoneId(),
        rider.status(),
        rider.kycStatus(),
        ts(rider.kycSubmittedAt()),
        ts(rider.kycReviewedAt()),
        rider.kycReviewedBy(),
        rider.kycRejectionReason(),
        rider.kycRejectionNotes(),
        rider.aadhaarVerified(),
        rider.avgRating(),
        rider.totalTrips(),
        rider.onTimePct(),
        rider.earningsWalletBalancePaise(),
        rider.codInHandPaise(),
        rider.dailyStreakDays(),
        rider.blockedReason(),
        rider.blockedBy(),
        ts(rider.blockedAt()),
        ts(rider.createdAt()),
        ts(rider.updatedAt()));
  }

  @Override
  public Optional<RiderRecord> findById(UUID id) {
    List<RiderRecord> rows =
        jdbc.query("SELECT * FROM riders WHERE id = ? AND deleted_at IS NULL", this::map, id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<RiderRecord> findByPhone(String phone) {
    List<RiderRecord> rows =
        jdbc.query("SELECT * FROM riders WHERE phone = ? AND deleted_at IS NULL", this::map, phone);
    return rows.stream().findFirst();
  }

  @Override
  public boolean existsByPhone(String phone) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(1) FROM riders WHERE phone = ? AND deleted_at IS NULL",
            Integer.class,
            phone);
    return count != null && count > 0;
  }

  @Override
  public void update(RiderRecord rider) {
    jdbc.update(
        """
        UPDATE riders SET
          name = ?, phone = ?, email = ?, vehicle_type = ?, vehicle_plate_number = ?,
          primary_zone_id = ?, status = ?, kyc_status = ?, kyc_submitted_at = ?,
          kyc_reviewed_at = ?, kyc_reviewed_by = ?, kyc_rejection_reason = ?,
          kyc_rejection_notes = ?, aadhaar_verified = ?, avg_rating = ?, total_trips = ?,
          on_time_pct = ?, earnings_wallet_balance_paise = ?, cod_in_hand_paise = ?,
          daily_streak_days = ?, blocked_reason = ?, blocked_by = ?, blocked_at = ?,
          updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        rider.name(),
        rider.phone(),
        rider.email(),
        rider.vehicleType(),
        rider.vehiclePlateNumber(),
        rider.primaryZoneId(),
        rider.status(),
        rider.kycStatus(),
        ts(rider.kycSubmittedAt()),
        ts(rider.kycReviewedAt()),
        rider.kycReviewedBy(),
        rider.kycRejectionReason(),
        rider.kycRejectionNotes(),
        rider.aadhaarVerified(),
        rider.avgRating(),
        rider.totalTrips(),
        rider.onTimePct(),
        rider.earningsWalletBalancePaise(),
        rider.codInHandPaise(),
        rider.dailyStreakDays(),
        rider.blockedReason(),
        rider.blockedBy(),
        ts(rider.blockedAt()),
        ts(rider.updatedAt()),
        rider.id());
  }

  @Override
  public void updateAvailability(UUID id, String status, UUID currentZoneId, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE riders SET status = ?, current_zone_id = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        status,
        currentZoneId,
        ts(updatedAt),
        id);
  }

  @Override
  public void updatePrimaryZone(UUID id, UUID primaryZoneId, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE riders SET primary_zone_id = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        primaryZoneId,
        ts(updatedAt),
        id);
  }

  @Override
  public void updateLastLocationAt(UUID id, Instant lastLocationAt) {
    jdbc.update(
        """
        UPDATE riders SET last_location_at = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        ts(lastLocationAt),
        ts(lastLocationAt),
        id);
  }

  @Override
  public long adjustCodInHand(UUID id, long deltaPaise, Instant updatedAt) {
    int updated =
        jdbc.update(
            """
            UPDATE riders
            SET cod_in_hand_paise = cod_in_hand_paise + ?, updated_at = ?
            WHERE id = ? AND deleted_at IS NULL
              AND cod_in_hand_paise + ? >= 0
            """,
            deltaPaise,
            ts(updatedAt),
            id,
            deltaPaise);
    if (updated == 0) {
      throw new IllegalStateException("COD float adjust failed for rider " + id);
    }
    Long bal =
        jdbc.queryForObject(
            "SELECT cod_in_hand_paise FROM riders WHERE id = ? AND deleted_at IS NULL",
            Long.class,
            id);
    return bal == null ? 0L : bal;
  }

  @Override
  public long adjustEarningsWallet(UUID id, long deltaPaise, Instant updatedAt) {
    int updated =
        jdbc.update(
            """
            UPDATE riders
            SET earnings_wallet_balance_paise = earnings_wallet_balance_paise + ?,
                total_trips = total_trips + CASE WHEN ? > 0 THEN 1 ELSE 0 END,
                updated_at = ?
            WHERE id = ? AND deleted_at IS NULL
              AND earnings_wallet_balance_paise + ? >= 0
            """,
            deltaPaise,
            deltaPaise,
            ts(updatedAt),
            id,
            deltaPaise);
    if (updated == 0) {
      throw new IllegalStateException("earnings wallet adjust failed for rider " + id);
    }
    Long bal =
        jdbc.queryForObject(
            """
            SELECT earnings_wallet_balance_paise FROM riders
            WHERE id = ? AND deleted_at IS NULL
            """,
            Long.class,
            id);
    return bal == null ? 0L : bal;
  }

  @Override
  public void updateStreak(
      UUID id,
      int dailyStreakDays,
      LocalDate lastDeliveryDate,
      boolean streakBonusPending,
      Instant updatedAt) {
    jdbc.update(
        """
        UPDATE riders SET
          daily_streak_days = ?,
          last_delivery_date = ?,
          streak_bonus_pending = streak_bonus_pending OR ?,
          updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        dailyStreakDays,
        lastDeliveryDate == null ? null : Date.valueOf(lastDeliveryDate),
        streakBonusPending,
        ts(updatedAt),
        id);
  }

  @Override
  public long payoutCarryForwardPaise(UUID id) {
    Long v =
        jdbc.queryForObject(
            """
            SELECT payout_carry_forward_paise FROM riders
            WHERE id = ? AND deleted_at IS NULL
            """,
            Long.class,
            id);
    return v == null ? 0L : v;
  }

  @Override
  public void setPayoutCarryForward(UUID id, long paise, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE riders SET payout_carry_forward_paise = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        paise,
        ts(updatedAt),
        id);
  }

  @Override
  public Optional<LocalDate> lastDeliveryDate(UUID id) {
    List<LocalDate> rows =
        jdbc.query(
            """
            SELECT last_delivery_date FROM riders
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) -> {
              Date d = rs.getDate("last_delivery_date");
              return d == null ? null : d.toLocalDate();
            },
            id);
    return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
  }

  @Override
  public boolean streakBonusPending(UUID id) {
    Boolean v =
        jdbc.queryForObject(
            """
            SELECT streak_bonus_pending FROM riders
            WHERE id = ? AND deleted_at IS NULL
            """,
            Boolean.class,
            id);
    return Boolean.TRUE.equals(v);
  }

  @Override
  public void clearStreakBonusPending(UUID id, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE riders SET streak_bonus_pending = FALSE, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        ts(updatedAt),
        id);
  }

  @Override
  public List<UUID> listIdsForPayoutCompute() {
    return jdbc.query(
        """
        SELECT id FROM riders
        WHERE deleted_at IS NULL
          AND (
            payout_carry_forward_paise > 0
            OR streak_bonus_pending = TRUE
            OR earnings_wallet_balance_paise > 0
            OR EXISTS (
              SELECT 1 FROM rider_trip_earnings e WHERE e.rider_id = riders.id
            )
          )
        """,
        (rs, i) -> (UUID) rs.getObject("id"));
  }

  @Override
  public PageResult list(ListFilter filter) {
    String statusClause = filter.status() == null ? "" : " AND status = ? ";
    String sortCol =
        switch (filter.sort()) {
          case "submitted_at" -> "kyc_submitted_at";
          case "name" -> "name";
          default -> "created_at";
        };
    String ord = "desc".equalsIgnoreCase(filter.order()) ? "DESC" : "ASC";
    int offset = (filter.page() - 1) * filter.limit();

    String countSql = "SELECT COUNT(1) FROM riders WHERE deleted_at IS NULL" + statusClause;
    Long total =
        filter.status() == null
            ? jdbc.queryForObject(countSql, Long.class)
            : jdbc.queryForObject(countSql, Long.class, filter.status());
    if (total == null) {
      total = 0L;
    }

    String sql =
        "SELECT * FROM riders WHERE deleted_at IS NULL"
            + statusClause
            + " ORDER BY "
            + sortCol
            + " "
            + ord
            + " NULLS LAST LIMIT ? OFFSET ?";
    List<RiderRecord> rows =
        filter.status() == null
            ? jdbc.query(sql, this::map, filter.limit(), offset)
            : jdbc.query(sql, this::map, filter.status(), filter.limit(), offset);
    return new PageResult(rows, total);
  }

  private RiderRecord map(ResultSet rs, int rowNum) throws SQLException {
    return new RiderRecord(
        (UUID) rs.getObject("id"),
        rs.getString("name"),
        rs.getString("phone"),
        rs.getString("email"),
        rs.getString("vehicle_type"),
        rs.getString("vehicle_plate_number"),
        (UUID) rs.getObject("primary_zone_id"),
        rs.getString("status"),
        rs.getString("kyc_status"),
        instant(rs.getTimestamp("kyc_submitted_at")),
        instant(rs.getTimestamp("kyc_reviewed_at")),
        (UUID) rs.getObject("kyc_reviewed_by"),
        rs.getString("kyc_rejection_reason"),
        rs.getString("kyc_rejection_notes"),
        rs.getBoolean("aadhaar_verified"),
        (BigDecimal) rs.getObject("avg_rating"),
        rs.getInt("total_trips"),
        (BigDecimal) rs.getObject("on_time_pct"),
        rs.getLong("earnings_wallet_balance_paise"),
        rs.getLong("cod_in_hand_paise"),
        rs.getInt("daily_streak_days"),
        rs.getString("blocked_reason"),
        (UUID) rs.getObject("blocked_by"),
        instant(rs.getTimestamp("blocked_at")),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")));
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}

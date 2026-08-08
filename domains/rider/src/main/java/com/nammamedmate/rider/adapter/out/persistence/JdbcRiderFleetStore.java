package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.RiderFleetStore;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRiderFleetStore implements RiderFleetStore {

  private final JdbcTemplate jdbc;

  public JdbcRiderFleetStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public FleetPage listFleet(FleetFilter filter) {
    StringBuilder where =
        new StringBuilder(
            " WHERE r.deleted_at IS NULL AND r.status NOT IN ('PENDING_KYC', 'BLOCKED') ");
    List<Object> args = new ArrayList<>();
    if (filter.zoneId() != null) {
      where.append(" AND COALESCE(r.current_zone_id, r.primary_zone_id) = ? ");
      args.add(filter.zoneId());
    }
    // status filter applied in service (needs active-order join); DB returns candidates
    Long total =
        jdbc.queryForObject("SELECT COUNT(1) FROM riders r" + where, Long.class, args.toArray());
    if (total == null) {
      total = 0L;
    }
    int offset = (filter.page() - 1) * filter.limit();
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(offset);
    List<FleetRiderRow> rows =
        jdbc.query(
            """
            SELECT r.id, r.name, r.phone, r.primary_zone_id, z.name AS zone_name,
                   r.vehicle_type, r.status, r.current_zone_id, r.last_location_at,
                   r.avg_rating, r.on_time_pct, r.daily_streak_days, r.earnings_wallet_balance_paise
            FROM riders r
            LEFT JOIN zones z ON z.id = COALESCE(r.current_zone_id, r.primary_zone_id)
            """
                + where
                + " ORDER BY r.updated_at DESC LIMIT ? OFFSET ?",
            this::map,
            pageArgs.toArray());
    return new FleetPage(rows, total);
  }

  @Override
  public List<FleetRiderRow> listByZone(UUID zoneId) {
    return jdbc.query(
        """
        SELECT r.id, r.name, r.phone, r.primary_zone_id, z.name AS zone_name,
               r.vehicle_type, r.status, r.current_zone_id, r.last_location_at,
               r.avg_rating, r.on_time_pct, r.daily_streak_days, r.earnings_wallet_balance_paise
        FROM riders r
        LEFT JOIN zones z ON z.id = COALESCE(r.current_zone_id, r.primary_zone_id)
        WHERE r.deleted_at IS NULL
          AND r.status NOT IN ('PENDING_KYC', 'BLOCKED')
          AND COALESCE(r.current_zone_id, r.primary_zone_id) = ?
        ORDER BY r.name ASC
        """,
        this::map,
        zoneId);
  }

  @Override
  public Optional<FleetRiderRow> findFleetRow(UUID riderId) {
    List<FleetRiderRow> rows =
        jdbc.query(
            """
            SELECT r.id, r.name, r.phone, r.primary_zone_id, z.name AS zone_name,
                   r.vehicle_type, r.status, r.current_zone_id, r.last_location_at,
                   r.avg_rating, r.on_time_pct, r.daily_streak_days, r.earnings_wallet_balance_paise
            FROM riders r
            LEFT JOIN zones z ON z.id = COALESCE(r.current_zone_id, r.primary_zone_id)
            WHERE r.id = ? AND r.deleted_at IS NULL
            """,
            this::map,
            riderId);
    return rows.stream().findFirst();
  }

  @Override
  public int countTripsToday(UUID riderId, Instant dayStartUtc, Instant dayEndUtc) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(trips_in_shift), 0) FROM rider_shifts
            WHERE rider_id = ? AND shift_start >= ? AND shift_start < ?
            """,
            Integer.class,
            riderId,
            Timestamp.from(dayStartUtc),
            Timestamp.from(dayEndUtc));
    return java.util.Objects.requireNonNullElse(n, 0);
  }

  @Override
  public long sumShiftEarningsTodayPaise(UUID riderId, Instant dayStartUtc, Instant dayEndUtc) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(earnings_in_shift_paise), 0) FROM rider_shifts
            WHERE rider_id = ? AND shift_start >= ? AND shift_start < ?
            """,
            Long.class,
            riderId,
            Timestamp.from(dayStartUtc),
            Timestamp.from(dayEndUtc));
    return java.util.Objects.requireNonNullElse(n, 0L);
  }

  private FleetRiderRow map(ResultSet rs, int rowNum) throws SQLException {
    Timestamp loc = rs.getTimestamp("last_location_at");
    return new FleetRiderRow(
        (UUID) rs.getObject("id"),
        rs.getString("name"),
        rs.getString("phone"),
        (UUID) rs.getObject("primary_zone_id"),
        rs.getString("zone_name"),
        rs.getString("vehicle_type"),
        rs.getString("status"),
        (UUID) rs.getObject("current_zone_id"),
        loc == null ? null : loc.toInstant(),
        (BigDecimal) rs.getObject("avg_rating"),
        (BigDecimal) rs.getObject("on_time_pct"),
        rs.getInt("daily_streak_days"),
        rs.getLong("earnings_wallet_balance_paise"));
  }
}

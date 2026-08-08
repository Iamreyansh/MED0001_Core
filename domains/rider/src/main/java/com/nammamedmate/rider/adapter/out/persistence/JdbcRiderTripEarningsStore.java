package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRiderTripEarningsStore implements RiderTripEarningsStore {

  private final JdbcTemplate jdbc;

  public JdbcRiderTripEarningsStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(EarningsRecord row) {
    jdbc.update(
        """
        INSERT INTO rider_trip_earnings (
          id, rider_id, order_id, assignment_id, delivery_date,
          base_pay_paise, tip_paise, incentive_bonus_paise, total_paise,
          on_time, customer_rating, distance_km, delivery_minutes, created_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        row.id(),
        row.riderId(),
        row.orderId(),
        row.assignmentId(),
        Date.valueOf(row.deliveryDate()),
        row.basePayPaise(),
        row.tipPaise(),
        row.incentiveBonusPaise(),
        row.totalPaise(),
        row.onTime(),
        row.customerRating(),
        row.distanceKm(),
        row.durationMinutes(),
        Timestamp.from(row.createdAt()));
  }

  @Override
  public PeriodTotals sumForRider(UUID riderId, LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbc.query(
        """
            SELECT COALESCE(SUM(base_pay_paise),0) AS base_sum,
                   COALESCE(SUM(incentive_bonus_paise),0) AS inc_sum,
                   COALESCE(SUM(tip_paise),0) AS tip_sum,
                   COALESCE(SUM(total_paise),0) AS total_sum,
                   COUNT(1)::int AS trips
            FROM rider_trip_earnings
            WHERE rider_id = ? AND delivery_date BETWEEN ? AND ?
            """,
        rs -> {
          rs.next();
          return new PeriodTotals(
              rs.getLong("base_sum"),
              rs.getLong("inc_sum"),
              rs.getLong("tip_sum"),
              rs.getLong("total_sum"),
              rs.getInt("trips"));
        },
        riderId,
        Date.valueOf(fromInclusive),
        Date.valueOf(toInclusive));
  }

  @Override
  public LifetimeTotals lifetime(UUID riderId) {
    return jdbc.query(
        """
        SELECT COALESCE(SUM(total_paise),0) AS total_sum, COUNT(1)::int AS trips
        FROM rider_trip_earnings WHERE rider_id = ?
        """,
        rs -> {
          rs.next();
          return new LifetimeTotals(rs.getLong("total_sum"), rs.getInt("trips"));
        },
        riderId);
  }

  @Override
  public List<TripView> listTrips(
      UUID riderId, LocalDate from, LocalDate to, int offset, int limit) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT e.order_id, o.order_number,
                   COALESCE(p.business_name, p.name, 'Pharmacy') AS pickup_pharmacy,
                   COALESCE(a.area_locality, a.city, '') AS delivery_area,
                   e.distance_km, COALESCE(e.delivery_minutes, 0) AS duration_minutes,
                   e.base_pay_paise, e.tip_paise, e.incentive_bonus_paise, e.total_paise,
                   e.on_time, e.customer_rating, e.created_at
            FROM rider_trip_earnings e
            LEFT JOIN orders o ON o.id = e.order_id
            LEFT JOIN pharmacies p ON p.id = o.pharmacy_id
            LEFT JOIN customer_addresses a ON a.id = o.delivery_address_id
            WHERE e.rider_id = ?
            """);
    if (from != null) {
      sql.append(" AND e.delivery_date >= ? ");
    }
    if (to != null) {
      sql.append(" AND e.delivery_date <= ? ");
    }
    sql.append(" ORDER BY e.created_at DESC LIMIT ? OFFSET ? ");

    Object[] args = args(riderId, from, to, limit, offset);
    return jdbc.query(sql.toString(), (rs, i) -> mapTrip(rs), args);
  }

  @Override
  public long countTrips(UUID riderId, LocalDate from, LocalDate to) {
    StringBuilder sql =
        new StringBuilder("SELECT COUNT(1) FROM rider_trip_earnings WHERE rider_id = ?");
    if (from != null) {
      sql.append(" AND delivery_date >= ? ");
    }
    if (to != null) {
      sql.append(" AND delivery_date <= ? ");
    }
    Object[] args = countArgs(riderId, from, to);
    Long n = jdbc.queryForObject(sql.toString(), Long.class, args);
    return n == null ? 0L : n;
  }

  @Override
  public Optional<BigDecimal> avgRating(UUID riderId) {
    BigDecimal avg =
        jdbc.queryForObject(
            """
            SELECT AVG(customer_rating)::numeric(3,2)
            FROM rider_trip_earnings
            WHERE rider_id = ? AND customer_rating IS NOT NULL
            """,
            BigDecimal.class,
            riderId);
    return Optional.ofNullable(avg);
  }

  @Override
  public BigDecimal totalDistanceKm(UUID riderId) {
    BigDecimal km =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(distance_km),0)
            FROM rider_trip_earnings WHERE rider_id = ?
            """,
            BigDecimal.class,
            riderId);
    return km == null ? BigDecimal.ZERO : km.setScale(1, java.math.RoundingMode.HALF_UP);
  }

  @Override
  public int countOnTime(UUID riderId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(1)::int FROM rider_trip_earnings WHERE rider_id = ? AND on_time = TRUE",
            Integer.class,
            riderId);
    return n == null ? 0 : n;
  }

  @Override
  public int countRated(UUID riderId) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(1)::int FROM rider_trip_earnings
            WHERE rider_id = ? AND customer_rating IS NOT NULL
            """,
            Integer.class,
            riderId);
    return n == null ? 0 : n;
  }

  @Override
  public List<UUID> distinctRidersWithEarnings(LocalDate from, LocalDate to) {
    return jdbc.query(
        """
        SELECT DISTINCT rider_id FROM rider_trip_earnings
        WHERE delivery_date BETWEEN ? AND ?
        """,
        (rs, i) -> (UUID) rs.getObject("rider_id"),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  private static TripView mapTrip(ResultSet rs) throws SQLException {
    Timestamp ts = rs.getTimestamp("created_at");
    return new TripView(
        (UUID) rs.getObject("order_id"),
        rs.getString("order_number"),
        rs.getString("pickup_pharmacy"),
        rs.getString("delivery_area"),
        rs.getBigDecimal("distance_km") == null ? BigDecimal.ZERO : rs.getBigDecimal("distance_km"),
        rs.getInt("duration_minutes"),
        rs.getLong("base_pay_paise"),
        rs.getLong("tip_paise"),
        rs.getLong("incentive_bonus_paise"),
        rs.getLong("total_paise"),
        rs.getBoolean("on_time"),
        (Integer) rs.getObject("customer_rating"),
        ts == null ? null : ts.toInstant());
  }

  private static Object[] args(UUID riderId, LocalDate from, LocalDate to, int limit, int offset) {
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
}

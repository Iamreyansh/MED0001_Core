package com.nammamedmate.analytics.adapter.out.persistence;

import com.nammamedmate.analytics.application.port.out.PlatformGeographyStore;
import com.nammamedmate.analytics.domain.PeriodResolver;
import com.nammamedmate.kernel.id.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPlatformGeographyStore implements PlatformGeographyStore {

  private final JdbcTemplate jdbc;

  public JdbcPlatformGeographyStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean zoneExists(UUID zoneId) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(1) FROM zones
            WHERE id = ? AND deleted_at IS NULL
            """,
            Integer.class,
            zoneId);
    return n != null && n > 0;
  }

  @Override
  public List<ZoneMetrics> liveZoneMetrics(Instant fromInclusive, Instant toExclusive) {
    return jdbc.query(
        """
        SELECT
          z.id AS zone_id,
          z.name AS zone_name,
          COALESCE(SUM(CASE WHEN o.status <> 'CANCELLED' THEN o.total_payable_paise ELSE 0 END), 0)
            AS gmv_paise,
          COUNT(o.id) FILTER (WHERE o.id IS NOT NULL) AS orders_count,
          COUNT(o.id) FILTER (
            WHERE o.delivered_at IS NOT NULL AND COALESCE(o.sla_breached, FALSE)
          ) AS sla_breached_count,
          COALESCE(SUM(
            CASE WHEN o.delivered_at IS NOT NULL
              THEN EXTRACT(EPOCH FROM (o.delivered_at - o.created_at))
              ELSE 0 END
          ), 0)::bigint AS total_delivery_seconds,
          COALESCE((
            SELECT COUNT(1)::numeric FROM pharmacies ph
            WHERE ph.zone_id = z.id AND ph.deleted_at IS NULL
          ), 0) AS pharmacies_count,
          COALESCE((
            SELECT CASE WHEN COUNT(1) = 0 THEN 0
              ELSE ROUND(100.0 * COUNT(1) FILTER (WHERE ph.is_online) / COUNT(1), 2) END
            FROM pharmacies ph
            WHERE ph.zone_id = z.id AND ph.deleted_at IS NULL
          ), 0) AS pharmacy_coverage_pct
        FROM zones z
        LEFT JOIN pharmacies p ON p.zone_id = z.id AND p.deleted_at IS NULL
        LEFT JOIN orders o ON o.pharmacy_id = p.id
          AND o.deleted_at IS NULL
          AND o.created_at >= ? AND o.created_at < ?
          AND o.status <> 'PAYMENT_PENDING'
        WHERE z.deleted_at IS NULL
          AND COALESCE(z.is_serviceable, z.active, TRUE)
        GROUP BY z.id, z.name
        """,
        (rs, i) ->
            new ZoneMetrics(
                (UUID) rs.getObject("zone_id"),
                rs.getString("zone_name"),
                rs.getLong("gmv_paise"),
                rs.getLong("orders_count"),
                rs.getLong("sla_breached_count"),
                rs.getLong("total_delivery_seconds"),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                rs.getInt("pharmacies_count"),
                rs.getBigDecimal("pharmacy_coverage_pct"),
                0),
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive));
  }

  @Override
  public List<ZoneMetrics> aggregatedZoneMetrics(LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbc.query(
        """
        SELECT
          d.zone_id,
          z.name AS zone_name,
          COALESCE(SUM(d.gmv_paise), 0) AS gmv_paise,
          COALESCE(SUM(d.orders_count), 0) AS orders_count,
          COALESCE(SUM(d.sla_breached_count), 0) AS sla_breached_count,
          COALESCE(SUM(d.total_delivery_seconds), 0) AS total_delivery_seconds,
          COALESCE(AVG(d.avg_riders_online), 0) AS avg_riders_online,
          COALESCE(MAX(d.pharmacies_count), 0) AS pharmacies_count,
          COALESCE(AVG(d.pharmacy_coverage_pct), 0) AS pharmacy_coverage_pct,
          COALESCE(SUM(d.unserved_attempts), 0) AS unserved_attempts
        FROM analytics_zone_daily d
        JOIN zones z ON z.id = d.zone_id AND z.deleted_at IS NULL
        WHERE d.snapshot_date >= ? AND d.snapshot_date <= ?
        GROUP BY d.zone_id, z.name
        """,
        (rs, i) ->
            new ZoneMetrics(
                (UUID) rs.getObject("zone_id"),
                rs.getString("zone_name"),
                rs.getLong("gmv_paise"),
                rs.getLong("orders_count"),
                rs.getLong("sla_breached_count"),
                rs.getLong("total_delivery_seconds"),
                scale2(rs.getBigDecimal("avg_riders_online")),
                rs.getInt("pharmacies_count"),
                scale2(rs.getBigDecimal("pharmacy_coverage_pct")),
                rs.getInt("unserved_attempts")),
        Date.valueOf(fromInclusive),
        Date.valueOf(toInclusive));
  }

  @Override
  public List<LiveRiderCount> liveRidersOnlineByZone() {
    return jdbc.query(
        """
        SELECT zone_id, COUNT(1) AS riders_online
        FROM (
          SELECT COALESCE(r.current_zone_id, r.primary_zone_id) AS zone_id
          FROM riders r
          WHERE r.deleted_at IS NULL
            AND r.status IN ('ONLINE', 'ON_TRIP')
            AND COALESCE(r.current_zone_id, r.primary_zone_id) IS NOT NULL
        ) t
        GROUP BY zone_id
        """,
        (rs, i) -> new LiveRiderCount((UUID) rs.getObject("zone_id"), rs.getLong("riders_online")));
  }

  @Override
  public List<HourlyDemandCell> heatmapCells(UUID zoneIdOrNull) {
    String sql =
        """
        SELECT h.zone_id, z.name AS zone_name, h.hour_of_day, h.day_of_week, h.avg_orders
        FROM analytics_zone_hourly_demand h
        JOIN zones z ON z.id = h.zone_id AND z.deleted_at IS NULL
        """
            + (zoneIdOrNull == null ? "" : " WHERE h.zone_id = ?")
            + " ORDER BY z.name, h.hour_of_day, h.day_of_week";
    if (zoneIdOrNull == null) {
      return jdbc.query(
          sql,
          (rs, i) ->
              new HourlyDemandCell(
                  (UUID) rs.getObject("zone_id"),
                  rs.getString("zone_name"),
                  rs.getInt("hour_of_day"),
                  rs.getInt("day_of_week"),
                  scale2(rs.getBigDecimal("avg_orders"))));
    }
    return jdbc.query(
        sql,
        (rs, i) ->
            new HourlyDemandCell(
                (UUID) rs.getObject("zone_id"),
                rs.getString("zone_name"),
                rs.getInt("hour_of_day"),
                rs.getInt("day_of_week"),
                scale2(rs.getBigDecimal("avg_orders"))),
        zoneIdOrNull);
  }

  @Override
  @Transactional
  public void refreshZoneDaily(LocalDate fromInclusive, LocalDate toInclusive) {
    LocalDate d = fromInclusive;
    while (!d.isAfter(toInclusive)) {
      final LocalDate day = d;
      Instant from = day.atStartOfDay(PeriodResolver.IST).toInstant();
      Instant to = day.plusDays(1).atStartOfDay(PeriodResolver.IST).toInstant();
      jdbc.update("DELETE FROM analytics_zone_daily WHERE snapshot_date = ?", Date.valueOf(day));
      List<Object[]> rows = new ArrayList<>();
      jdbc.query(
          """
          SELECT
            z.id AS zone_id,
            COALESCE(SUM(CASE WHEN o.status <> 'CANCELLED' THEN o.total_payable_paise ELSE 0 END), 0)
              AS gmv_paise,
            COUNT(o.id) FILTER (WHERE o.id IS NOT NULL) AS orders_count,
            COUNT(o.id) FILTER (
              WHERE o.delivered_at IS NOT NULL AND COALESCE(o.sla_breached, FALSE)
            ) AS sla_breached_count,
            COALESCE(SUM(
              CASE WHEN o.delivered_at IS NOT NULL
                THEN EXTRACT(EPOCH FROM (o.delivered_at - o.created_at))
                ELSE 0 END
            ), 0)::bigint AS total_delivery_seconds,
            COALESCE((
              SELECT COUNT(1)::numeric FROM riders r
              WHERE r.deleted_at IS NULL
                AND r.status IN ('ONLINE', 'ON_TRIP')
                AND COALESCE(r.current_zone_id, r.primary_zone_id) = z.id
            ), 0) AS avg_riders_online,
            COALESCE((
              SELECT COUNT(1) FROM pharmacies ph
              WHERE ph.zone_id = z.id AND ph.deleted_at IS NULL
            ), 0) AS pharmacies_count,
            COALESCE((
              SELECT CASE WHEN COUNT(1) = 0 THEN 0
                ELSE ROUND(100.0 * COUNT(1) FILTER (WHERE ph.is_online) / COUNT(1), 2) END
              FROM pharmacies ph
              WHERE ph.zone_id = z.id AND ph.deleted_at IS NULL
            ), 0) AS pharmacy_coverage_pct
          FROM zones z
          LEFT JOIN pharmacies p ON p.zone_id = z.id AND p.deleted_at IS NULL
          LEFT JOIN orders o ON o.pharmacy_id = p.id
            AND o.deleted_at IS NULL
            AND o.created_at >= ? AND o.created_at < ?
            AND o.status <> 'PAYMENT_PENDING'
          WHERE z.deleted_at IS NULL
          GROUP BY z.id
          """,
          rs -> {
            while (rs.next()) {
              rows.add(
                  new Object[] {
                    Ids.newId(),
                    rs.getObject("zone_id"),
                    Date.valueOf(day),
                    rs.getLong("gmv_paise"),
                    (int) rs.getLong("orders_count"),
                    (int) rs.getLong("sla_breached_count"),
                    rs.getLong("total_delivery_seconds"),
                    rs.getBigDecimal("avg_riders_online"),
                    rs.getInt("pharmacies_count"),
                    rs.getBigDecimal("pharmacy_coverage_pct"),
                    0
                  });
            }
            return null;
          },
          Timestamp.from(from),
          Timestamp.from(to));
      jdbc.batchUpdate(
          """
          INSERT INTO analytics_zone_daily (
            id, zone_id, snapshot_date, gmv_paise, orders_count, sla_breached_count,
            total_delivery_seconds, avg_riders_online, pharmacies_count,
            pharmacy_coverage_pct, unserved_attempts)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          rows);
      d = day.plusDays(1);
    }
  }

  @Override
  @Transactional
  public void refreshHourlyDemand(LocalDate asOfExclusiveEnd, int windowDays) {
    LocalDate fromDate = asOfExclusiveEnd.minusDays(windowDays);
    Instant from = fromDate.atStartOfDay(PeriodResolver.IST).toInstant();
    Instant to = asOfExclusiveEnd.atStartOfDay(PeriodResolver.IST).toInstant();
    Instant computedAt = Instant.now();
    jdbc.update("DELETE FROM analytics_zone_hourly_demand");
    List<Object[]> rows = new ArrayList<>();
    jdbc.query(
        """
        SELECT
          z.id AS zone_id,
          EXTRACT(HOUR FROM o.created_at AT TIME ZONE 'Asia/Kolkata')::int AS hour_of_day,
          EXTRACT(DOW FROM o.created_at AT TIME ZONE 'Asia/Kolkata')::int AS day_of_week,
          ROUND(COUNT(o.id)::numeric / ?, 2) AS avg_orders
        FROM zones z
        JOIN pharmacies p ON p.zone_id = z.id AND p.deleted_at IS NULL
        JOIN orders o ON o.pharmacy_id = p.id
          AND o.deleted_at IS NULL
          AND o.created_at >= ? AND o.created_at < ?
          AND o.status <> 'PAYMENT_PENDING'
        WHERE z.deleted_at IS NULL
        GROUP BY z.id, hour_of_day, day_of_week
        """,
        rs -> {
          while (rs.next()) {
            rows.add(
                new Object[] {
                  Ids.newId(),
                  rs.getObject("zone_id"),
                  rs.getInt("hour_of_day"),
                  rs.getInt("day_of_week"),
                  rs.getBigDecimal("avg_orders"),
                  Timestamp.from(computedAt)
                });
          }
          return null;
        },
        BigDecimal.valueOf(windowDays),
        Timestamp.from(from),
        Timestamp.from(to));
    if (!rows.isEmpty()) {
      jdbc.batchUpdate(
          """
          INSERT INTO analytics_zone_hourly_demand (
            id, zone_id, hour_of_day, day_of_week, avg_orders, computed_at)
          VALUES (?, ?, ?, ?, ?, ?)
          """,
          rows);
    }
  }

  private static BigDecimal scale2(BigDecimal v) {
    if (v == null) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return v.setScale(2, RoundingMode.HALF_UP);
  }
}

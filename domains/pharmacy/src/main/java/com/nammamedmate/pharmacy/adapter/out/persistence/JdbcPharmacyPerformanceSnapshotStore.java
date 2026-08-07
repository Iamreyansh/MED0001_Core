package com.nammamedmate.pharmacy.adapter.out.persistence;

import com.nammamedmate.pharmacy.application.port.out.PharmacyPerformanceSnapshotStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPharmacyPerformanceSnapshotStore implements PharmacyPerformanceSnapshotStore {

  private final JdbcTemplate jdbc;

  public JdbcPharmacyPerformanceSnapshotStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<SnapshotRow> find(UUID pharmacyId, String period) {
    List<SnapshotRow> rows =
        jdbc.query(
            """
            SELECT * FROM pharmacy_performance_snapshot
            WHERE pharmacy_id = ? AND period = ?::pharmacy_performance_period
            """,
            this::mapRow,
            pharmacyId,
            period);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public void upsert(SnapshotRow row, Instant updatedAt) {
    jdbc.update(
        """
        INSERT INTO pharmacy_performance_snapshot (
            id, pharmacy_id, period, period_start, period_end,
            orders_received, orders_fulfilled, orders_cancelled,
            fill_rate_pct, on_time_prep_pct, cancel_rate_pct, out_of_stock_rate_pct,
            avg_prep_minutes, complaint_count, avg_rating, review_count, gmv_period_paise,
            consecutive_low_fill_days, fill_rate_trend, cancel_rate_trend, computed_at, updated_at
        ) VALUES (
            ?, ?, ?::pharmacy_performance_period, ?, ?,
            ?, ?, ?,
            ?, ?, ?, ?,
            ?, ?, ?, ?, ?,
            ?, ?, ?, ?, ?
        )
        ON CONFLICT (pharmacy_id, period) DO UPDATE SET
            period_start = EXCLUDED.period_start,
            period_end = EXCLUDED.period_end,
            orders_received = EXCLUDED.orders_received,
            orders_fulfilled = EXCLUDED.orders_fulfilled,
            orders_cancelled = EXCLUDED.orders_cancelled,
            fill_rate_pct = EXCLUDED.fill_rate_pct,
            on_time_prep_pct = EXCLUDED.on_time_prep_pct,
            cancel_rate_pct = EXCLUDED.cancel_rate_pct,
            out_of_stock_rate_pct = EXCLUDED.out_of_stock_rate_pct,
            avg_prep_minutes = EXCLUDED.avg_prep_minutes,
            complaint_count = EXCLUDED.complaint_count,
            avg_rating = EXCLUDED.avg_rating,
            review_count = EXCLUDED.review_count,
            gmv_period_paise = EXCLUDED.gmv_period_paise,
            consecutive_low_fill_days = EXCLUDED.consecutive_low_fill_days,
            fill_rate_trend = EXCLUDED.fill_rate_trend,
            cancel_rate_trend = EXCLUDED.cancel_rate_trend,
            computed_at = EXCLUDED.computed_at,
            updated_at = EXCLUDED.updated_at
        """,
        row.id(),
        row.pharmacyId(),
        row.period(),
        row.periodStart(),
        row.periodEnd(),
        row.ordersReceived(),
        row.ordersFulfilled(),
        row.ordersCancelled(),
        row.fillRatePct(),
        row.onTimePrepPct(),
        row.cancelRatePct(),
        row.outOfStockRatePct(),
        row.avgPrepMinutes(),
        row.complaintCount(),
        row.avgRating(),
        row.reviewCount(),
        row.gmvPeriodPaise(),
        row.consecutiveLowFillDays(),
        row.fillRateTrend(),
        row.cancelRateTrend(),
        row.computedAt(),
        updatedAt);
  }

  private SnapshotRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new SnapshotRow(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("period"),
        rs.getObject("period_start", LocalDate.class),
        rs.getObject("period_end", LocalDate.class),
        rs.getInt("orders_received"),
        rs.getInt("orders_fulfilled"),
        rs.getInt("orders_cancelled"),
        rs.getBigDecimal("fill_rate_pct"),
        rs.getBigDecimal("on_time_prep_pct"),
        rs.getBigDecimal("cancel_rate_pct"),
        rs.getBigDecimal("out_of_stock_rate_pct"),
        rs.getBigDecimal("avg_prep_minutes"),
        rs.getInt("complaint_count"),
        rs.getBigDecimal("avg_rating"),
        rs.getInt("review_count"),
        rs.getLong("gmv_period_paise"),
        rs.getShort("consecutive_low_fill_days"),
        rs.getString("fill_rate_trend"),
        rs.getString("cancel_rate_trend"),
        ts(rs, "computed_at"));
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}

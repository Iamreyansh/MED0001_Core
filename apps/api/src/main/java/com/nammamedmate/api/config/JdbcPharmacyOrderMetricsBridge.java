package com.nammamedmate.api.config;

import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * EPIC-010 bridge: pharmacy admin metrics from {@code orders}. Ratings stay empty (no ratings
 * table).
 */
final class JdbcPharmacyOrderMetricsBridge implements PharmacyOrderMetricsPort {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final BigDecimal ZERO = new BigDecimal("0.00");
  private static final BigDecimal ZERO_PREP = new BigDecimal("0.0");

  private final JdbcTemplate jdbc;

  JdbcPharmacyOrderMetricsBridge(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Performance performance(UUID pharmacyId) {
    Instant since = Instant.now().minusSeconds(30L * 24 * 3600);
    Integer orders30d =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)::int FROM orders
            WHERE pharmacy_id = ? AND deleted_at IS NULL AND created_at >= ?
            """,
            Integer.class,
            pharmacyId,
            Timestamp.from(since));
    Long gmv30d =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(total_payable_paise), 0) FROM orders
            WHERE pharmacy_id = ? AND deleted_at IS NULL AND created_at >= ?
              AND status <> 'CANCELLED'
            """,
            Long.class,
            pharmacyId,
            Timestamp.from(since));
    Integer cancelled =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)::int FROM orders
            WHERE pharmacy_id = ? AND deleted_at IS NULL AND created_at >= ?
              AND status = 'CANCELLED'
            """,
            Integer.class,
            pharmacyId,
            Timestamp.from(since));
    int total = orders30d == null ? 0 : orders30d;
    int cancelCount = cancelled == null ? 0 : cancelled;
    BigDecimal cancelRate =
        total == 0
            ? ZERO
            : BigDecimal.valueOf(cancelCount * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
    return new Performance(ZERO, ZERO, cancelRate, ZERO, 0, total, gmv30d == null ? 0L : gmv30d);
  }

  @Override
  public CommissionLedger commissionLedger(UUID pharmacyId) {
    return new CommissionLedger(0L, 0L, 0L, 0L, null, null);
  }

  @Override
  public List<RecentOrder> recentOrders(UUID pharmacyId, int limit) {
    int lim = Math.max(1, Math.min(limit, 100));
    return jdbc.query(
        """
        SELECT id, order_number, status, total_payable_paise, created_at
        FROM orders
        WHERE pharmacy_id = ? AND deleted_at IS NULL
        ORDER BY created_at DESC
        LIMIT ?
        """,
        (rs, rowNum) ->
            new RecentOrder(
                UUID.fromString(rs.getString("id")),
                rs.getString("order_number"),
                rs.getString("status"),
                rs.getLong("total_payable_paise"),
                rs.getTimestamp("created_at").toInstant()),
        pharmacyId,
        lim);
  }

  @Override
  public PeriodMetrics periodMetrics(UUID pharmacyId, LocalDate periodEnd, int days) {
    LocalDate start = periodEnd.minusDays(Math.max(days, 1) - 1L);
    Instant from = start.atStartOfDay(IST).toInstant();
    Instant to = periodEnd.plusDays(1).atStartOfDay(IST).toInstant();
    Integer received =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)::int FROM orders
            WHERE pharmacy_id = ? AND deleted_at IS NULL
              AND created_at >= ? AND created_at < ?
            """,
            Integer.class,
            pharmacyId,
            Timestamp.from(from),
            Timestamp.from(to));
    Integer fulfilled =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)::int FROM orders
            WHERE pharmacy_id = ? AND deleted_at IS NULL
              AND created_at >= ? AND created_at < ?
              AND status = 'DELIVERED'
            """,
            Integer.class,
            pharmacyId,
            Timestamp.from(from),
            Timestamp.from(to));
    Integer cancelled =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)::int FROM orders
            WHERE pharmacy_id = ? AND deleted_at IS NULL
              AND created_at >= ? AND created_at < ?
              AND status = 'CANCELLED'
            """,
            Integer.class,
            pharmacyId,
            Timestamp.from(from),
            Timestamp.from(to));
    Long gmv =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(total_payable_paise), 0) FROM orders
            WHERE pharmacy_id = ? AND deleted_at IS NULL
              AND created_at >= ? AND created_at < ?
              AND status <> 'CANCELLED'
            """,
            Long.class,
            pharmacyId,
            Timestamp.from(from),
            Timestamp.from(to));
    int r = received == null ? 0 : received;
    int c = cancelled == null ? 0 : cancelled;
    BigDecimal cancelRate =
        r == 0 ? ZERO : BigDecimal.valueOf(c * 100.0 / r).setScale(2, RoundingMode.HALF_UP);
    return new PeriodMetrics(
        r,
        fulfilled == null ? 0 : fulfilled,
        c,
        ZERO,
        ZERO,
        cancelRate,
        ZERO,
        ZERO_PREP,
        0,
        ZERO,
        0,
        gmv == null ? 0L : gmv,
        (short) 0);
  }

  @Override
  public RatingListResult listRatings(
      UUID pharmacyId, Integer ratingFilter, String sort, String order, int limit, int offset) {
    return new RatingListResult(ZERO, 0, Map.of(5, 0, 4, 0, 3, 0, 2, 0, 1, 0), List.of(), 0L);
  }

  @Override
  public OrderListResult listOrders(
      UUID pharmacyId, String status, LocalDate fromDate, LocalDate toDate, int limit, int offset) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT id, order_number, status, total_payable_paise, items, prescription_id,
                   created_at, delivered_at, accepted_at
            FROM orders
            WHERE pharmacy_id = ? AND deleted_at IS NULL
            """);
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    if (status != null && !status.isBlank()) {
      sql.append(" AND status = ?");
      args.add(status.trim().toUpperCase());
    }
    if (fromDate != null) {
      sql.append(" AND created_at >= ?");
      args.add(Timestamp.from(fromDate.atStartOfDay(IST).toInstant()));
    }
    if (toDate != null) {
      sql.append(" AND created_at < ?");
      args.add(Timestamp.from(toDate.plusDays(1).atStartOfDay(IST).toInstant()));
    }
    Long total =
        jdbc.queryForObject("SELECT COUNT(*) FROM (" + sql + ") t", Long.class, args.toArray());
    sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
    args.add(Math.max(1, Math.min(limit, 100)));
    args.add(Math.max(0, offset));
    List<AdminOrderDetail> rows =
        jdbc.query(
            sql.toString(),
            (rs, rowNum) -> {
              String itemsJson = rs.getString("items");
              int itemCount =
                  itemsJson == null || itemsJson.isBlank()
                      ? 0
                      : Math.max(0, itemsJson.split("product_id").length - 1);
              Instant created = rs.getTimestamp("created_at").toInstant();
              Timestamp acceptedTs = rs.getTimestamp("accepted_at");
              Timestamp deliveredTs = rs.getTimestamp("delivered_at");
              int prepMinutes = 0;
              if (acceptedTs != null && deliveredTs != null) {
                prepMinutes =
                    (int)
                        Math.max(
                            0,
                            (deliveredTs.toInstant().getEpochSecond()
                                    - acceptedTs.toInstant().getEpochSecond())
                                / 60);
              }
              return new AdminOrderDetail(
                  UUID.fromString(rs.getString("id")),
                  rs.getString("order_number"),
                  rs.getString("status"),
                  "",
                  itemCount,
                  rs.getLong("total_payable_paise"),
                  prepMinutes,
                  false,
                  rs.getObject("prescription_id") != null,
                  created,
                  deliveredTs == null ? null : deliveredTs.toInstant());
            },
            args.toArray());
    return new OrderListResult(rows, total == null ? 0L : total);
  }

  @Override
  public long annualGmvYtdPaise(UUID pharmacyId) {
    LocalDate today = LocalDate.now(IST);
    LocalDate yearStart = LocalDate.of(today.getYear(), 1, 1);
    return gmvForPeriodPaise(pharmacyId, yearStart, today);
  }

  @Override
  public long gmvForPeriodPaise(UUID pharmacyId, LocalDate periodStart, LocalDate periodEnd) {
    Instant from = periodStart.atStartOfDay(IST).toInstant();
    Instant to = periodEnd.plusDays(1).atStartOfDay(IST).toInstant();
    Long gmv =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(total_payable_paise), 0) FROM orders
            WHERE pharmacy_id = ? AND deleted_at IS NULL
              AND created_at >= ? AND created_at < ?
              AND status <> 'CANCELLED'
            """,
            Long.class,
            pharmacyId,
            Timestamp.from(from),
            Timestamp.from(to));
    return gmv == null ? 0L : gmv;
  }
}

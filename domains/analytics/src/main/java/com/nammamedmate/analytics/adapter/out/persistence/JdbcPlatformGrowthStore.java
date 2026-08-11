package com.nammamedmate.analytics.adapter.out.persistence;

import com.nammamedmate.analytics.application.port.out.AcquisitionSourcePort;
import com.nammamedmate.analytics.application.port.out.AcquisitionSourcePort.Source;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore;
import com.nammamedmate.analytics.domain.AnalyticsMath;
import com.nammamedmate.analytics.domain.PeriodResolver;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPlatformGrowthStore implements PlatformGrowthStore {

  private static final String[] ALL_SOURCES = {"ORGANIC", "REFERRAL", "AD", "PARTNER"};

  private final JdbcTemplate jdbc;
  private final AcquisitionSourcePort acquisitionSources;

  public JdbcPlatformGrowthStore(JdbcTemplate jdbc, AcquisitionSourcePort acquisitionSources) {
    this.jdbc = jdbc;
    this.acquisitionSources = acquisitionSources;
  }

  @Override
  public GrowthTotals liveGrowth(Instant fromInclusive, Instant toExclusive) {
    List<GrowthTotals> rows =
        jdbc.query(
            """
            SELECT
              COUNT(DISTINCT o.customer_id) AS active_customers,
              COUNT(DISTINCT o.customer_id) FILTER (
                WHERE o.customer_id IN (
                  SELECT customer_id FROM orders
                  WHERE deleted_at IS NULL
                  GROUP BY customer_id
                  HAVING MIN(created_at) >= ? AND MIN(created_at) < ?
                )
              ) AS new_customers,
              COUNT(DISTINCT o.customer_id) FILTER (
                WHERE o.customer_id IN (
                  SELECT customer_id FROM orders
                  WHERE deleted_at IS NULL
                    AND created_at >= ? AND created_at < ?
                  GROUP BY customer_id HAVING COUNT(1) >= 2
                )
              ) AS repeat_customers
            FROM orders o
            WHERE o.deleted_at IS NULL
              AND o.created_at >= ? AND o.created_at < ?
            """,
            (rs, i) ->
                new GrowthTotals(
                    rs.getLong("active_customers"),
                    rs.getLong("new_customers"),
                    rs.getLong("repeat_customers")),
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive),
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive),
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    return emptyGrowth(rows);
  }

  @Override
  public GrowthTotals aggregatedGrowth(LocalDate fromInclusive, LocalDate toInclusive) {
    List<GrowthTotals> rows =
        jdbc.query(
            """
            SELECT
              COALESCE(SUM(active_customers), 0) AS active_customers,
              COALESCE(SUM(new_customers), 0) AS new_customers,
              COALESCE(SUM(repeat_customers), 0) AS repeat_customers
            FROM analytics_daily_snapshots
            WHERE zone_id IS NULL
              AND snapshot_date >= ? AND snapshot_date <= ?
            """,
            (rs, i) ->
                new GrowthTotals(
                    rs.getLong("active_customers"),
                    rs.getLong("new_customers"),
                    rs.getLong("repeat_customers")),
            Date.valueOf(fromInclusive),
            Date.valueOf(toInclusive));
    return emptyGrowth(rows);
  }

  @Override
  public List<CohortCell> cohortMatrix(int cohortCount) {
    return jdbc.query(
        """
        SELECT cohort_week, cohort_size, elapsed_week, retained_count, retention_pct, computed_at
        FROM analytics_cohort_retention
        WHERE cohort_week IN (
          SELECT cohort_week FROM (
            SELECT cohort_week, MAX(computed_at) AS computed_at
            FROM analytics_cohort_retention
            GROUP BY cohort_week
            ORDER BY cohort_week DESC
            LIMIT ?
          ) recent
        )
        ORDER BY cohort_week DESC, elapsed_week ASC
        """,
        (rs, i) ->
            new CohortCell(
                rs.getString("cohort_week"),
                rs.getInt("cohort_size"),
                rs.getInt("elapsed_week"),
                rs.getInt("retained_count"),
                rs.getBigDecimal("retention_pct"),
                rs.getTimestamp("computed_at").toInstant()),
        cohortCount);
  }

  @Override
  public Optional<Instant> cohortLastComputedAt() {
    Instant at =
        jdbc.query(
            "SELECT MAX(computed_at) FROM analytics_cohort_retention",
            rs -> rs.next() && rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null);
    return Optional.ofNullable(at);
  }

  @Override
  public Optional<Month1Retention> month1Retention(LocalDate asOfIst) {
    LocalDate currentWeekStart = AnalyticsMath.isoWeekStart(asOfIst);
    // Walk calendar months backward; monthly cohort = first ISO week of that month.
    for (int monthsBack = 1; monthsBack <= 24; monthsBack++) {
      LocalDate month = asOfIst.minusMonths(monthsBack).withDayOfMonth(1);
      LocalDate cohortStart = AnalyticsMath.firstIsoWeekOfMonth(month);
      String cohortWeek = AnalyticsMath.isoWeekLabel(cohortStart);
      // Week-4 must be complete: cohortStart + 5 weeks Monday <= current week start
      if (cohortStart.plusWeeks(5).isAfter(currentWeekStart)) {
        continue;
      }
      List<Month1Retention> found =
          jdbc.query(
              """
              SELECT cohort_week, retention_pct
              FROM analytics_cohort_retention
              WHERE cohort_week = ? AND elapsed_week = 4
              LIMIT 1
              """,
              (rs, i) ->
                  new Month1Retention(
                      rs.getString("cohort_week"), rs.getBigDecimal("retention_pct")),
              cohortWeek);
      if (!found.isEmpty()) {
        return Optional.of(found.getFirst());
      }
    }
    return Optional.empty();
  }

  @Override
  public List<AcquisitionRow> liveAcquisition(Instant fromInclusive, Instant toExclusive) {
    List<CustomerOrderAgg> aggs =
        jdbc.query(
            """
            WITH first_orders AS (
              SELECT customer_id, MIN(created_at) AS first_at
              FROM orders
              WHERE deleted_at IS NULL
              GROUP BY customer_id
            ),
            new_in_period AS (
              SELECT customer_id FROM first_orders
              WHERE first_at >= ? AND first_at < ?
            )
            SELECT
              o.customer_id,
              COUNT(1) AS orders,
              COALESCE(SUM(CASE WHEN o.status <> 'CANCELLED' THEN o.total_payable_paise ELSE 0 END), 0)
                AS gmv_paise
            FROM orders o
            JOIN new_in_period n ON n.customer_id = o.customer_id
            WHERE o.deleted_at IS NULL
              AND o.created_at >= ? AND o.created_at < ?
            GROUP BY o.customer_id
            """,
            (rs, i) ->
                new CustomerOrderAgg(
                    (UUID) rs.getObject("customer_id"),
                    rs.getLong("orders"),
                    rs.getLong("gmv_paise")),
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive),
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));

    EnumMap<Source, long[]> buckets = new EnumMap<>(Source.class);
    for (Source s : Source.values()) {
      buckets.put(s, new long[] {0, 0, 0}); // newUsers, orders, gmv
    }
    for (CustomerOrderAgg a : aggs) {
      Source src = acquisitionSources.sourceForCustomer(a.customerId());
      long[] b = buckets.get(src);
      b[0] += 1;
      b[1] += a.orders();
      b[2] += a.gmvPaise();
    }
    List<AcquisitionRow> out = new ArrayList<>();
    for (Source s : Source.values()) {
      long[] b = buckets.get(s);
      if (b[0] == 0) {
        continue;
      }
      out.add(new AcquisitionRow(s.name(), b[0], b[1], b[2]));
    }
    return out;
  }

  @Override
  public List<AcquisitionRow> aggregatedAcquisition(
      LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbc.query(
        """
        SELECT source,
               COALESCE(SUM(new_users), 0) AS new_users,
               COALESCE(SUM(orders), 0) AS orders,
               COALESCE(SUM(gmv_paise), 0) AS gmv_paise
        FROM analytics_acquisition_daily
        WHERE snapshot_date >= ? AND snapshot_date <= ?
        GROUP BY source
        ORDER BY source
        """,
        (rs, i) ->
            new AcquisitionRow(
                rs.getString("source"),
                rs.getLong("new_users"),
                rs.getLong("orders"),
                rs.getLong("gmv_paise")),
        Date.valueOf(fromInclusive),
        Date.valueOf(toInclusive));
  }

  @Override
  public List<SpendRow> campaignSpend(LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbc.query(
        """
        SELECT source, COALESCE(SUM(spend_rs), 0) AS spend_rs
        FROM campaign_spend
        WHERE period_from <= ? AND period_to >= ?
        GROUP BY source
        """,
        (rs, i) -> new SpendRow(rs.getString("source"), rs.getBigDecimal("spend_rs")),
        Date.valueOf(toInclusive),
        Date.valueOf(fromInclusive));
  }

  @Override
  public List<OrderTrendPoint> orderTrendDaily(Instant fromInclusive, Instant toExclusive) {
    return jdbc.query(
        """
        WITH first_orders AS (
          SELECT customer_id, MIN(created_at) AS first_at
          FROM orders
          WHERE deleted_at IS NULL
          GROUP BY customer_id
        )
        SELECT
          (o.created_at AT TIME ZONE 'Asia/Kolkata')::date AS order_date,
          COUNT(1) AS total_orders,
          COUNT(1) FILTER (WHERE fo.first_at = o.created_at) AS new_customer_orders,
          COUNT(1) FILTER (WHERE fo.first_at < o.created_at) AS returning_customer_orders
        FROM orders o
        JOIN first_orders fo ON fo.customer_id = o.customer_id
        WHERE o.deleted_at IS NULL
          AND o.created_at >= ? AND o.created_at < ?
        GROUP BY order_date
        ORDER BY order_date
        """,
        (rs, i) ->
            new OrderTrendPoint(
                rs.getDate("order_date").toLocalDate(),
                rs.getLong("total_orders"),
                rs.getLong("new_customer_orders"),
                rs.getLong("returning_customer_orders")),
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive));
  }

  @Override
  public List<OrderTrendPoint> orderTrendWeekly(Instant fromInclusive, Instant toExclusive) {
    return jdbc.query(
        """
        WITH first_orders AS (
          SELECT customer_id, MIN(created_at) AS first_at
          FROM orders
          WHERE deleted_at IS NULL
          GROUP BY customer_id
        )
        SELECT
          date_trunc('week', o.created_at AT TIME ZONE 'Asia/Kolkata')::date AS week_start,
          COUNT(1) AS total_orders,
          COUNT(1) FILTER (WHERE fo.first_at = o.created_at) AS new_customer_orders,
          COUNT(1) FILTER (WHERE fo.first_at < o.created_at) AS returning_customer_orders
        FROM orders o
        JOIN first_orders fo ON fo.customer_id = o.customer_id
        WHERE o.deleted_at IS NULL
          AND o.created_at >= ? AND o.created_at < ?
        GROUP BY week_start
        ORDER BY week_start
        """,
        (rs, i) ->
            new OrderTrendPoint(
                rs.getDate("week_start").toLocalDate(),
                rs.getLong("total_orders"),
                rs.getLong("new_customer_orders"),
                rs.getLong("returning_customer_orders")),
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive));
  }

  @Override
  @Transactional
  public void refreshCohortRetention(int cohortWeeks, Instant computedAt) {
    LocalDate todayIst = LocalDate.ofInstant(computedAt, PeriodResolver.IST);
    LocalDate currentWeekStart = AnalyticsMath.isoWeekStart(todayIst);

    for (int c = 0; c < cohortWeeks; c++) {
      LocalDate cohortStart = currentWeekStart.minusWeeks(c);
      String cohortWeek = AnalyticsMath.isoWeekLabel(cohortStart);
      Instant cohortFrom = cohortStart.atStartOfDay(PeriodResolver.IST).toInstant();
      Instant cohortTo = cohortStart.plusWeeks(1).atStartOfDay(PeriodResolver.IST).toInstant();

      List<UUID> members =
          jdbc.query(
              """
              SELECT customer_id FROM (
                SELECT customer_id, MIN(created_at) AS first_at
                FROM orders
                WHERE deleted_at IS NULL
                GROUP BY customer_id
              ) f
              WHERE first_at >= ? AND first_at < ?
              """,
              (rs, i) -> (UUID) rs.getObject(1),
              Timestamp.from(cohortFrom),
              Timestamp.from(cohortTo));
      int cohortSize = members.size();
      jdbc.update("DELETE FROM analytics_cohort_retention WHERE cohort_week = ?", cohortWeek);

      int maxElapsed =
          (int)
              Math.min(
                  12, java.time.temporal.ChronoUnit.WEEKS.between(cohortStart, currentWeekStart));
      for (int elapsed = 0; elapsed <= maxElapsed; elapsed++) {
        LocalDate weekStart = cohortStart.plusWeeks(elapsed);
        Instant wFrom = weekStart.atStartOfDay(PeriodResolver.IST).toInstant();
        Instant wTo = weekStart.plusWeeks(1).atStartOfDay(PeriodResolver.IST).toInstant();
        int retained;
        if (cohortSize == 0) {
          retained = 0;
        } else if (elapsed == 0) {
          retained = cohortSize;
        } else {
          Integer count =
              jdbc.queryForObject(
                  """
                  SELECT COUNT(DISTINCT o.customer_id)
                  FROM orders o
                  WHERE o.deleted_at IS NULL
                    AND o.created_at >= ? AND o.created_at < ?
                    AND o.customer_id IN (
                      SELECT customer_id FROM (
                        SELECT customer_id, MIN(created_at) AS first_at
                        FROM orders
                        WHERE deleted_at IS NULL
                        GROUP BY customer_id
                      ) f
                      WHERE first_at >= ? AND first_at < ?
                    )
                  """,
                  Integer.class,
                  Timestamp.from(wFrom),
                  Timestamp.from(wTo),
                  Timestamp.from(cohortFrom),
                  Timestamp.from(cohortTo));
          retained = count != null ? count : 0;
        }
        BigDecimal pct =
            elapsed == 0 && cohortSize > 0
                ? BigDecimal.valueOf(100).setScale(2)
                : AnalyticsMath.retentionPct(retained, cohortSize);
        jdbc.update(
            """
            INSERT INTO analytics_cohort_retention
              (id, cohort_week, cohort_size, elapsed_week, retained_count, retention_pct, computed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            UUID.randomUUID(),
            cohortWeek,
            cohortSize,
            elapsed,
            retained,
            pct,
            Timestamp.from(computedAt));
      }
    }
  }

  @Override
  @Transactional
  public void refreshAcquisitionDaily(LocalDate fromInclusive, LocalDate toInclusive) {
    for (LocalDate day = fromInclusive; !day.isAfter(toInclusive); day = day.plusDays(1)) {
      Instant from = day.atStartOfDay(PeriodResolver.IST).toInstant();
      Instant to = day.plusDays(1).atStartOfDay(PeriodResolver.IST).toInstant();
      jdbc.update(
          "DELETE FROM analytics_acquisition_daily WHERE snapshot_date = ?", Date.valueOf(day));
      List<AcquisitionRow> rows = liveAcquisition(from, to);
      Map<String, AcquisitionRow> bySource = new LinkedHashMap<>();
      for (String s : ALL_SOURCES) {
        bySource.put(s, new AcquisitionRow(s, 0, 0, 0));
      }
      for (AcquisitionRow r : rows) {
        bySource.put(r.source(), r);
      }
      for (AcquisitionRow r : bySource.values()) {
        if (r.newUsers() == 0) {
          continue;
        }
        jdbc.update(
            """
            INSERT INTO analytics_acquisition_daily
              (id, snapshot_date, source, new_users, orders, gmv_paise)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            UUID.randomUUID(),
            Date.valueOf(day),
            r.source(),
            (int) r.newUsers(),
            (int) r.orders(),
            r.gmvPaise());
      }
    }
  }

  private static GrowthTotals emptyGrowth(List<GrowthTotals> rows) {
    if (rows == null || rows.isEmpty()) {
      return new GrowthTotals(0, 0, 0);
    }
    return rows.getFirst();
  }

  private record CustomerOrderAgg(UUID customerId, long orders, long gmvPaise) {}
}

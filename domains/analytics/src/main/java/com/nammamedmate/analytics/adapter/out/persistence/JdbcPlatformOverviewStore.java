package com.nammamedmate.analytics.adapter.out.persistence;

import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore;
import com.nammamedmate.analytics.domain.AnalyticsMath;
import com.nammamedmate.analytics.domain.PeriodResolver;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPlatformOverviewStore implements PlatformOverviewStore {

  private final JdbcTemplate jdbc;

  public JdbcPlatformOverviewStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public KpiTotals liveKpis(Instant fromInclusive, Instant toExclusive) {
    List<KpiTotals> rows =
        jdbc.query(
            """
            SELECT
              COALESCE(SUM(CASE WHEN o.status <> 'CANCELLED' THEN o.total_payable_paise ELSE 0 END), 0)
                AS gmv,
              COUNT(1) AS orders_count,
              COUNT(1) FILTER (WHERE o.status = 'DELIVERED') AS delivered_count,
              COUNT(1) FILTER (WHERE o.status = 'CANCELLED') AS cancelled_count,
              COALESCE(SUM(CASE WHEN o.status = 'CANCELLED' THEN o.total_payable_paise ELSE 0 END), 0)
                AS cancellations_paise,
              COALESCE((
                SELECT SUM(r.amount_paise) FROM refund r
                WHERE r.created_at >= ? AND r.created_at < ?
                  AND r.status = 'PROCESSED'
              ), 0) AS refunds_paise,
              COALESCE((
                SELECT SUM(fl.credit_paise) FROM financial_ledger fl
                WHERE fl.entry_type = 'COMMISSION'
                  AND fl.created_at >= ? AND fl.created_at < ?
              ), 0) AS commission_paise,
              COALESCE((
                SELECT SUM(fl.debit_paise) FROM financial_ledger fl
                WHERE fl.entry_type = 'PAYOUT_PHARMACY'
                  AND fl.created_at >= ? AND fl.created_at < ?
              ), 0) AS cogs_paise,
              COUNT(DISTINCT o.customer_id) AS active_customers,
              COUNT(DISTINCT o.customer_id) FILTER (
                WHERE o.customer_id IN (
                  SELECT customer_id FROM orders
                  WHERE deleted_at IS NULL
                    AND created_at >= ? AND created_at < ?
                  GROUP BY customer_id HAVING COUNT(1) >= 2
                )
              ) AS repeat_customers,
              0 AS new_customers
            FROM orders o
            WHERE o.deleted_at IS NULL
              AND o.created_at >= ? AND o.created_at < ?
            """,
            (rs, i) ->
                new KpiTotals(
                    rs.getLong("gmv"),
                    rs.getLong("orders_count"),
                    rs.getLong("delivered_count"),
                    rs.getLong("cancelled_count"),
                    rs.getLong("refunds_paise"),
                    rs.getLong("cancellations_paise"),
                    rs.getLong("commission_paise"),
                    rs.getLong("cogs_paise"),
                    rs.getLong("active_customers"),
                    rs.getLong("repeat_customers"),
                    rs.getLong("new_customers")),
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive),
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive),
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive),
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive),
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    return emptyOrFirst(rows);
  }

  @Override
  public KpiTotals aggregatedKpis(LocalDate fromInclusive, LocalDate toInclusive) {
    List<KpiTotals> rows =
        jdbc.query(
            """
            SELECT
              COALESCE(SUM(gmv_paise), 0) AS gmv,
              COALESCE(SUM(orders_count), 0) AS orders_count,
              COALESCE(SUM(delivered_count), 0) AS delivered_count,
              COALESCE(SUM(cancelled_count), 0) AS cancelled_count,
              COALESCE(SUM(refunds_paise), 0) AS refunds_paise,
              COALESCE(SUM(cancellations_paise), 0) AS cancellations_paise,
              COALESCE(SUM(commission_paise), 0) AS commission_paise,
              COALESCE(SUM(cogs_estimate_paise), 0) AS cogs_paise,
              COALESCE(SUM(active_customers), 0) AS active_customers,
              COALESCE(SUM(repeat_customers), 0) AS repeat_customers,
              COALESCE(SUM(new_customers), 0) AS new_customers
            FROM analytics_daily_snapshots
            WHERE zone_id IS NULL
              AND snapshot_date >= ? AND snapshot_date <= ?
            """,
            (rs, i) ->
                new KpiTotals(
                    rs.getLong("gmv"),
                    rs.getLong("orders_count"),
                    rs.getLong("delivered_count"),
                    rs.getLong("cancelled_count"),
                    rs.getLong("refunds_paise"),
                    rs.getLong("cancellations_paise"),
                    rs.getLong("commission_paise"),
                    rs.getLong("cogs_paise"),
                    rs.getLong("active_customers"),
                    rs.getLong("repeat_customers"),
                    rs.getLong("new_customers")),
            Date.valueOf(fromInclusive),
            Date.valueOf(toInclusive));
    return emptyOrFirst(rows);
  }

  @Override
  public List<GmvTrendPoint> liveGmvTrend(Instant fromInclusive, Instant toExclusive) {
    return jdbc.query(
        """
        SELECT
          (date_trunc('day', o.created_at AT TIME ZONE 'Asia/Kolkata'))::date AS d,
          COALESCE(SUM(CASE WHEN o.status <> 'CANCELLED' THEN o.total_payable_paise ELSE 0 END), 0)
            AS gmv
        FROM orders o
        WHERE o.deleted_at IS NULL
          AND o.created_at >= ? AND o.created_at < ?
        GROUP BY 1
        ORDER BY 1
        """,
        (rs, i) -> new GmvTrendPoint(rs.getDate("d").toLocalDate(), rs.getLong("gmv")),
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive));
  }

  @Override
  public List<GmvTrendPoint> aggregatedGmvTrend(LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbc.query(
        """
        SELECT snapshot_date AS d, COALESCE(SUM(gmv_paise), 0) AS gmv
        FROM analytics_daily_snapshots
        WHERE zone_id IS NULL
          AND snapshot_date >= ? AND snapshot_date <= ?
        GROUP BY snapshot_date
        ORDER BY snapshot_date
        """,
        (rs, i) -> new GmvTrendPoint(rs.getDate("d").toLocalDate(), rs.getLong("gmv")),
        Date.valueOf(fromInclusive),
        Date.valueOf(toInclusive));
  }

  @Override
  public List<CategoryMixRow> liveCategoryMix(Instant fromInclusive, Instant toExclusive) {
    // ponytail: bucket by rx_required on order line JSON until catalogue category join is needed
    return jdbc.query(
        """
        SELECT category, COALESCE(SUM(line_gmv), 0) AS gmv
        FROM (
          SELECT
            CASE
              WHEN COALESCE((elem->>'rxRequired')::boolean, false) THEN 'PRESCRIPTION_MEDICINES'
              WHEN LOWER(COALESCE(elem->>'name', '')) LIKE '%vitamin%'
                OR LOWER(COALESCE(elem->>'name', '')) LIKE '%supplement%'
                THEN 'WELLNESS_SUPPLEMENTS'
              WHEN LOWER(COALESCE(elem->>'name', '')) LIKE '%device%'
                OR LOWER(COALESCE(elem->>'name', '')) LIKE '%equip%'
                THEN 'DEVICES_EQUIPMENT'
              ELSE 'OTC_MEDICINES'
            END AS category,
            COALESCE((elem->>'lineTotalPaise')::bigint, 0) AS line_gmv
          FROM orders o
          CROSS JOIN LATERAL jsonb_array_elements(o.items) AS elem
          WHERE o.deleted_at IS NULL
            AND o.status <> 'CANCELLED'
            AND o.created_at >= ? AND o.created_at < ?
        ) x
        GROUP BY category
        ORDER BY gmv DESC
        """,
        (rs, i) -> new CategoryMixRow(rs.getString("category"), rs.getLong("gmv")),
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive));
  }

  @Override
  public List<CategoryMixRow> aggregatedCategoryMix(
      LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbc.query(
        """
        SELECT category, COALESCE(SUM(gmv_paise), 0) AS gmv
        FROM analytics_category_mix_daily
        WHERE snapshot_date >= ? AND snapshot_date <= ?
        GROUP BY category
        ORDER BY gmv DESC
        """,
        (rs, i) -> new CategoryMixRow(rs.getString("category"), rs.getLong("gmv")),
        Date.valueOf(fromInclusive),
        Date.valueOf(toInclusive));
  }

  @Override
  public List<PaymentMixRow> livePaymentMix(Instant fromInclusive, Instant toExclusive) {
    return jdbc.query(
        """
        SELECT o.payment_method AS method, COUNT(1) AS orders
        FROM orders o
        WHERE o.deleted_at IS NULL
          AND o.status <> 'CANCELLED'
          AND o.created_at >= ? AND o.created_at < ?
        GROUP BY o.payment_method
        ORDER BY orders DESC
        """,
        (rs, i) -> new PaymentMixRow(rs.getString("method"), rs.getLong("orders")),
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive));
  }

  @Override
  public List<PaymentMixRow> aggregatedPaymentMix(LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbc.query(
        """
        SELECT payment_method AS method, COALESCE(SUM(orders_count), 0) AS orders
        FROM analytics_payment_mix_daily
        WHERE snapshot_date >= ? AND snapshot_date <= ?
        GROUP BY payment_method
        ORDER BY orders DESC
        """,
        (rs, i) -> new PaymentMixRow(rs.getString("method"), rs.getLong("orders")),
        Date.valueOf(fromInclusive),
        Date.valueOf(toInclusive));
  }

  @Override
  public List<ZoneSalesRow> liveSalesByZone(Instant fromInclusive, Instant toExclusive) {
    return jdbc.query(
        """
        SELECT
          p.zone_id,
          COALESCE(z.name, 'Unknown') AS zone_name,
          COALESCE(SUM(CASE WHEN o.status <> 'CANCELLED' THEN o.total_payable_paise ELSE 0 END), 0)
            AS gmv,
          COUNT(1) AS orders
        FROM orders o
        JOIN pharmacies p ON p.id = o.pharmacy_id
        LEFT JOIN zones z ON z.id = p.zone_id
        WHERE o.deleted_at IS NULL
          AND o.created_at >= ? AND o.created_at < ?
        GROUP BY p.zone_id, z.name
        ORDER BY gmv DESC
        """,
        (rs, i) -> {
          UUID zoneId = (UUID) rs.getObject("zone_id");
          return new ZoneSalesRow(
              zoneId, rs.getString("zone_name"), rs.getLong("gmv"), rs.getLong("orders"));
        },
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive));
  }

  @Override
  public List<ZoneSalesRow> aggregatedSalesByZone(LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbc.query(
        """
        SELECT
          s.zone_id,
          COALESCE(z.name, 'Unknown') AS zone_name,
          COALESCE(SUM(s.gmv_paise), 0) AS gmv,
          COALESCE(SUM(s.orders_count), 0) AS orders
        FROM analytics_daily_snapshots s
        LEFT JOIN zones z ON z.id = s.zone_id
        WHERE s.zone_id IS NOT NULL
          AND s.snapshot_date >= ? AND s.snapshot_date <= ?
        GROUP BY s.zone_id, z.name
        ORDER BY gmv DESC
        """,
        (rs, i) -> {
          UUID zoneId = (UUID) rs.getObject("zone_id");
          return new ZoneSalesRow(
              zoneId, rs.getString("zone_name"), rs.getLong("gmv"), rs.getLong("orders"));
        },
        Date.valueOf(fromInclusive),
        Date.valueOf(toInclusive));
  }

  @Override
  public List<PharmacyLeader> topPharmacies(Instant fromInclusive, Instant toExclusive, int topN) {
    return jdbc.query(
        """
        SELECT
          o.pharmacy_id,
          COALESCE(NULLIF(TRIM(p.business_name), ''), p.name) AS name,
          COALESCE(z.name, '') AS area,
          COALESCE(m.rating, 0) AS rating,
          COUNT(1) AS orders,
          COALESCE(SUM(CASE WHEN o.status <> 'CANCELLED' THEN o.total_payable_paise ELSE 0 END), 0)
            AS gmv,
          COALESCE(m.fill_rate_pct, 0) AS fill_rate_pct
        FROM orders o
        JOIN pharmacies p ON p.id = o.pharmacy_id
        LEFT JOIN zones z ON z.id = p.zone_id
        LEFT JOIN pharmacy_directory_metrics m ON m.pharmacy_id = p.id
        WHERE o.deleted_at IS NULL
          AND o.created_at >= ? AND o.created_at < ?
        GROUP BY o.pharmacy_id, p.business_name, p.name, z.name, m.rating, m.fill_rate_pct
        ORDER BY gmv DESC, name ASC
        LIMIT ?
        """,
        (rs, i) ->
            new PharmacyLeader(
                (UUID) rs.getObject("pharmacy_id"),
                rs.getString("name"),
                rs.getString("area"),
                rs.getDouble("rating"),
                rs.getLong("orders"),
                rs.getLong("gmv"),
                rs.getDouble("fill_rate_pct")),
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive),
        topN);
  }

  @Override
  public List<RiderLeader> topRiders(Instant fromInclusive, Instant toExclusive, int topN) {
    return jdbc.query(
        """
        SELECT
          o.rider_id,
          r.name,
          COALESCE(z.name, '') AS zone,
          COUNT(1) AS trips,
          COALESCE(r.on_time_pct, 0) AS on_time_pct,
          COALESCE(r.avg_rating, 0) AS rating,
          COALESCE(SUM(
            CASE WHEN o.status = 'DELIVERED' THEN o.delivery_fee_paise ELSE 0 END
          ), 0) AS earnings_paise
        FROM orders o
        JOIN riders r ON r.id = o.rider_id AND r.deleted_at IS NULL
        LEFT JOIN zones z ON z.id = COALESCE(r.current_zone_id, r.primary_zone_id)
        WHERE o.deleted_at IS NULL
          AND o.rider_id IS NOT NULL
          AND o.status = 'DELIVERED'
          AND o.created_at >= ? AND o.created_at < ?
        GROUP BY o.rider_id, r.name, z.name, r.on_time_pct, r.avg_rating
        ORDER BY trips DESC, r.name ASC
        LIMIT ?
        """,
        (rs, i) ->
            new RiderLeader(
                (UUID) rs.getObject("rider_id"),
                rs.getString("name"),
                rs.getString("zone"),
                rs.getLong("trips"),
                rs.getDouble("on_time_pct"),
                rs.getDouble("rating"),
                rs.getLong("earnings_paise")),
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive),
        topN);
  }

  @Override
  @Transactional
  public void refreshDailySnapshots(LocalDate fromInclusive, LocalDate toInclusive) {
    for (LocalDate day = fromInclusive; !day.isAfter(toInclusive); day = day.plusDays(1)) {
      Instant from = day.atStartOfDay(PeriodResolver.IST).toInstant();
      Instant to = day.plusDays(1).atStartOfDay(PeriodResolver.IST).toInstant();
      upsertPlatformSnapshot(day, from, to);
      upsertPaymentMix(day, from, to);
      upsertCategoryMix(day, from, to);
      upsertZoneSnapshots(day, from, to);
    }
  }

  private void upsertPlatformSnapshot(LocalDate day, Instant from, Instant to) {
    KpiTotals k = liveKpis(from, to);
    long net =
        AnalyticsMath.netRevenuePaise(k.gmvPaise(), k.refundsPaise(), k.cancellationsPaise());
    jdbc.update(
        """
        INSERT INTO analytics_daily_snapshots (
          id, snapshot_date, gmv_paise, orders_count, delivered_count, cancelled_count,
          net_revenue_paise, commission_paise, refunds_paise, cancellations_paise,
          cogs_estimate_paise, active_customers, repeat_customers, new_customers, zone_id, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NOW())
        ON CONFLICT (snapshot_date) WHERE zone_id IS NULL DO UPDATE SET
          gmv_paise = EXCLUDED.gmv_paise,
          orders_count = EXCLUDED.orders_count,
          delivered_count = EXCLUDED.delivered_count,
          cancelled_count = EXCLUDED.cancelled_count,
          net_revenue_paise = EXCLUDED.net_revenue_paise,
          commission_paise = EXCLUDED.commission_paise,
          refunds_paise = EXCLUDED.refunds_paise,
          cancellations_paise = EXCLUDED.cancellations_paise,
          cogs_estimate_paise = EXCLUDED.cogs_estimate_paise,
          active_customers = EXCLUDED.active_customers,
          repeat_customers = EXCLUDED.repeat_customers,
          new_customers = EXCLUDED.new_customers
        """,
        UUID.randomUUID(),
        Date.valueOf(day),
        k.gmvPaise(),
        (int) k.ordersCount(),
        (int) k.deliveredCount(),
        (int) k.cancelledCount(),
        net,
        k.commissionPaise(),
        k.refundsPaise(),
        k.cancellationsPaise(),
        k.cogsEstimatePaise(),
        (int) k.activeCustomers(),
        (int) k.repeatCustomers(),
        (int) k.newCustomers());
  }

  private void upsertPaymentMix(LocalDate day, Instant from, Instant to) {
    jdbc.update(
        "DELETE FROM analytics_payment_mix_daily WHERE snapshot_date = ?", Date.valueOf(day));
    List<PaymentMixRow> rows = livePaymentMix(from, to);
    for (PaymentMixRow row : rows) {
      jdbc.update(
          """
          INSERT INTO analytics_payment_mix_daily
            (id, snapshot_date, payment_method, orders_count, gmv_paise)
          VALUES (?, ?, ?, ?, 0)
          """,
          UUID.randomUUID(),
          Date.valueOf(day),
          row.method(),
          (int) row.ordersCount());
    }
  }

  private void upsertCategoryMix(LocalDate day, Instant from, Instant to) {
    jdbc.update(
        "DELETE FROM analytics_category_mix_daily WHERE snapshot_date = ?", Date.valueOf(day));
    List<CategoryMixRow> rows = liveCategoryMix(from, to);
    for (CategoryMixRow row : rows) {
      jdbc.update(
          """
          INSERT INTO analytics_category_mix_daily
            (id, snapshot_date, category, gmv_paise, units_sold)
          VALUES (?, ?, ?, ?, 0)
          """,
          UUID.randomUUID(),
          Date.valueOf(day),
          row.category(),
          row.gmvPaise());
    }
  }

  private void upsertZoneSnapshots(LocalDate day, Instant from, Instant to) {
    jdbc.update(
        "DELETE FROM analytics_daily_snapshots WHERE snapshot_date = ? AND zone_id IS NOT NULL",
        Date.valueOf(day));
    List<ZoneSalesRow> zones = liveSalesByZone(from, to);
    for (ZoneSalesRow z : zones) {
      if (z.zoneId() == null) {
        continue;
      }
      jdbc.update(
          """
          INSERT INTO analytics_daily_snapshots (
            id, snapshot_date, gmv_paise, orders_count, delivered_count, cancelled_count,
            net_revenue_paise, commission_paise, refunds_paise, cancellations_paise,
            cogs_estimate_paise, active_customers, repeat_customers, new_customers, zone_id, created_at
          ) VALUES (?, ?, ?, ?, 0, 0, ?, 0, 0, 0, 0, 0, 0, 0, ?, NOW())
          """,
          UUID.randomUUID(),
          Date.valueOf(day),
          z.gmvPaise(),
          (int) z.ordersCount(),
          z.gmvPaise(),
          z.zoneId());
    }
  }

  private static KpiTotals emptyOrFirst(List<KpiTotals> rows) {
    if (rows == null || rows.isEmpty()) {
      return new KpiTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
    return rows.getFirst();
  }
}

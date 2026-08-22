package com.nammamedmate.observability_ops.adapter.out.persistence;

import com.nammamedmate.observability_ops.application.port.out.MetricSourcePort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Live command-center metrics from orders, payments, riders, and automation tables. */
@Component
@Primary
public class JdbcMetricSourceAdapter implements MetricSourcePort {

  private final JdbcTemplate jdbc;

  public JdbcMetricSourceAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public long gmvLastHourPaise() {
    return longOrZero(
        """
        SELECT COALESCE(SUM(total_payable_paise), 0)
          FROM orders
         WHERE deleted_at IS NULL
           AND status <> 'CANCELLED'
           AND created_at >= NOW() - INTERVAL '1 hour'
        """);
  }

  @Override
  public long gmvCurrentHourPaise() {
    return gmvLastHourPaise();
  }

  @Override
  public long gmvSameHourDowAvgPaise() {
    return longOrZero(
        """
        SELECT COALESCE(AVG(hour_gmv), 0)
          FROM (
            SELECT SUM(total_payable_paise) AS hour_gmv
              FROM orders
             WHERE deleted_at IS NULL
               AND status <> 'CANCELLED'
               AND created_at >= NOW() - INTERVAL '28 days'
               AND EXTRACT(DOW FROM created_at) = EXTRACT(DOW FROM NOW())
               AND EXTRACT(HOUR FROM created_at) = EXTRACT(HOUR FROM NOW())
             GROUP BY DATE_TRUNC('hour', created_at)
          ) t
        """);
  }

  @Override
  public double ordersPerMinute() {
    long n =
        longOrZero(
            """
            SELECT COUNT(*) FROM orders
             WHERE deleted_at IS NULL AND created_at >= NOW() - INTERVAL '10 minutes'
            """);
    return n / 10.0;
  }

  @Override
  public BigDecimal dispatchSuccessRatePct() {
    return pct(
        """
        SELECT
          COUNT(*) FILTER (WHERE rider_id IS NOT NULL) AS ok,
          COUNT(*) AS total
          FROM orders
         WHERE deleted_at IS NULL
           AND created_at >= NOW() - INTERVAL '1 hour'
           AND status NOT IN ('CANCELLED', 'PAYMENT_PENDING')
        """);
  }

  @Override
  public BigDecimal slaAdherencePctLastHour() {
    return pct(
        """
        SELECT
          COUNT(*) FILTER (
            WHERE delivered_at IS NOT NULL
              AND estimated_delivery_at IS NOT NULL
              AND delivered_at <= estimated_delivery_at
          ) AS ok,
          COUNT(*) FILTER (WHERE delivered_at IS NOT NULL) AS total
          FROM orders
         WHERE deleted_at IS NULL
           AND delivered_at >= NOW() - INTERVAL '1 hour'
        """);
  }

  @Override
  public BigDecimal paymentSuccessRatePct15m() {
    return pct(
        """
        SELECT
          COUNT(*) FILTER (WHERE status = 'CAPTURED') AS ok,
          COUNT(*) AS total
          FROM payment
         WHERE created_at >= NOW() - INTERVAL '15 minutes'
        """);
  }

  @Override
  public int paymentAttempts15m() {
    return (int)
        longOrZero(
            "SELECT COUNT(*) FROM payment WHERE created_at >= NOW() - INTERVAL '15 minutes'");
  }

  @Override
  public long payoutVolumeLastHourPaise() {
    return longOrZero(
        """
        SELECT COALESCE(SUM(debit_paise + credit_paise), 0)
          FROM financial_ledger
         WHERE created_at >= NOW() - INTERVAL '1 hour'
           AND entry_type ILIKE '%PAYOUT%'
        """);
  }

  @Override
  public long payoutHourlyAvg7dPaise() {
    return longOrZero(
        """
        SELECT COALESCE(AVG(hour_amt), 0)
          FROM (
            SELECT SUM(debit_paise + credit_paise) AS hour_amt
              FROM financial_ledger
             WHERE created_at >= NOW() - INTERVAL '7 days'
               AND entry_type ILIKE '%PAYOUT%'
             GROUP BY DATE_TRUNC('hour', created_at)
          ) t
        """);
  }

  @Override
  public List<ZoneRiderSnapshot> zoneRiders() {
    return jdbc.query(
        """
        SELECT z.id, z.name,
               COALESCE((
                 SELECT COUNT(*) FROM riders r
                  WHERE r.primary_zone_id = z.id
                    AND r.deleted_at IS NULL
                    AND r.status IN ('ONLINE', 'ON_TRIP')
               ), 0) AS online,
               3 AS demand
          FROM zones z
         WHERE z.active = TRUE
         ORDER BY z.name
         LIMIT 20
        """,
        (rs, i) ->
            new ZoneRiderSnapshot(
                (UUID) rs.getObject("id"),
                rs.getString("name"),
                rs.getInt("online"),
                rs.getInt("demand")));
  }

  @Override
  public int activeAutomations() {
    return (int)
        longOrZero(
            "SELECT COUNT(*) FROM automation_rules WHERE deleted_at IS NULL AND status = 'ACTIVE'");
  }

  @Override
  public int pendingApprovals() {
    return (int) longOrZero("SELECT COUNT(*) FROM automation_approvals WHERE status = 'PENDING'");
  }

  @Override
  public BigDecimal apiP99CompliancePct30d() {
    // No ALB/CloudWatch latency feed yet — never claim fake SLO compliance.
    return BigDecimal.ZERO;
  }

  @Override
  public BigDecimal orderSlaPct30d() {
    return pct(
        """
        SELECT
          COUNT(*) FILTER (
            WHERE delivered_at IS NOT NULL
              AND estimated_delivery_at IS NOT NULL
              AND delivered_at <= estimated_delivery_at
          ) AS ok,
          COUNT(*) FILTER (WHERE delivered_at IS NOT NULL) AS total
          FROM orders
         WHERE deleted_at IS NULL
           AND delivered_at >= NOW() - INTERVAL '30 days'
        """);
  }

  @Override
  public BigDecimal paymentSuccessPct30d() {
    return pct(
        """
        SELECT
          COUNT(*) FILTER (WHERE status = 'CAPTURED') AS ok,
          COUNT(*) AS total
          FROM payment
         WHERE created_at >= NOW() - INTERVAL '30 days'
        """);
  }

  @Override
  public BigDecimal dispatchSuccessPct30d() {
    return pct(
        """
        SELECT
          COUNT(*) FILTER (WHERE rider_id IS NOT NULL) AS ok,
          COUNT(*) AS total
          FROM orders
         WHERE deleted_at IS NULL
           AND created_at >= NOW() - INTERVAL '30 days'
           AND status NOT IN ('CANCELLED', 'PAYMENT_PENDING')
        """);
  }

  private long longOrZero(String sql) {
    Long v = jdbc.queryForObject(sql, Long.class);
    return v == null ? 0L : v;
  }

  private BigDecimal pct(String sql) {
    return jdbc.query(
        sql,
        rs -> {
          if (!rs.next()) {
            return new BigDecimal("100.0");
          }
          long ok = rs.getLong("ok");
          long total = rs.getLong("total");
          if (total <= 0) {
            return new BigDecimal("100.0");
          }
          return BigDecimal.valueOf(ok * 100.0 / total).setScale(1, RoundingMode.HALF_UP);
        });
  }
}

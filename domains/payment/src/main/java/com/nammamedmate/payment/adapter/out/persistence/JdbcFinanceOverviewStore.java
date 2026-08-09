package com.nammamedmate.payment.adapter.out.persistence;

import com.nammamedmate.payment.application.port.out.FinanceOverviewQueryPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFinanceOverviewStore implements FinanceOverviewQueryPort {

  private final JdbcTemplate jdbc;

  public JdbcFinanceOverviewStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public KpiSnapshot kpi(Instant dayStartInclusive, Instant dayEndExclusive) {
    List<KpiSnapshot> rows =
        jdbc.query(
            """
            SELECT
              (SELECT COALESCE(SUM(amount_paise), 0) FROM payment
                 WHERE status = 'CAPTURED'
                   AND created_at >= ? AND created_at < ?) AS gmv_today,
              (SELECT COALESCE(SUM(credit_paise), 0) FROM financial_ledger
                 WHERE entry_type = 'COMMISSION'
                   AND created_at >= ? AND created_at < ?) AS platform_revenue,
              (SELECT COALESCE(SUM(net_paid_paise), 0) FROM settlement
                 WHERE deleted_at IS NULL AND status = 'PENDING_RELEASE') AS pharmacy_due,
              (SELECT COALESCE(SUM(net_payout_paise), 0) FROM rider_payouts
                 WHERE deleted_at IS NULL AND status = 'PENDING') AS rider_due,
              (SELECT COUNT(1) FROM refund WHERE status = 'PENDING') AS refunds_pending,
              (SELECT COALESCE(SUM(amount_paise), 0) FROM refund
                 WHERE status = 'PENDING') AS refunds_pending_value,
              (SELECT COALESCE(SUM(cod_amount_paise), 0) FROM cod_collections
                 WHERE is_deposited = FALSE) AS cod_in_hand,
              (SELECT COALESCE(SUM(balance_paise), 0) FROM wallets) AS wallet_total,
              (SELECT COALESCE(SUM(COALESCE(gateway_fee_paise, 0)), 0) FROM payment
                 WHERE status = 'CAPTURED'
                   AND created_at >= ? AND created_at < ?) AS gateway_fees
            """,
            (rs, i) ->
                new KpiSnapshot(
                    rs.getLong("gmv_today"),
                    rs.getLong("platform_revenue"),
                    rs.getLong("pharmacy_due"),
                    rs.getLong("rider_due"),
                    rs.getLong("refunds_pending"),
                    rs.getLong("refunds_pending_value"),
                    rs.getLong("cod_in_hand"),
                    rs.getLong("wallet_total"),
                    rs.getLong("gateway_fees")),
            Timestamp.from(dayStartInclusive),
            Timestamp.from(dayEndExclusive),
            Timestamp.from(dayStartInclusive),
            Timestamp.from(dayEndExclusive),
            Timestamp.from(dayStartInclusive),
            Timestamp.from(dayEndExclusive));
    return rows == null || rows.isEmpty()
        ? new KpiSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0)
        : rows.getFirst();
  }

  @Override
  public PeriodTotals periodTotals(Instant fromInclusive, Instant toExclusive) {
    List<PeriodTotals> ledger =
        jdbc.query(
            """
            SELECT
              COALESCE(SUM(CASE WHEN entry_type = 'ORDER_GMV' THEN credit_paise ELSE 0 END), 0)
                AS gmv,
              COALESCE(SUM(CASE WHEN entry_type = 'COMMISSION' THEN credit_paise ELSE 0 END), 0)
                AS commission,
              COALESCE(SUM(CASE WHEN entry_type = 'REFUND' THEN debit_paise ELSE 0 END), 0)
                AS refunds,
              COALESCE(SUM(CASE WHEN entry_type = 'GATEWAY_FEE' THEN debit_paise ELSE 0 END), 0)
                AS gateway_fees,
              COALESCE(SUM(CASE WHEN entry_type = 'PAYOUT_PHARMACY' THEN debit_paise ELSE 0 END), 0)
                AS pharmacy_payout,
              COALESCE(SUM(CASE WHEN entry_type = 'PAYOUT_RIDER' THEN debit_paise ELSE 0 END), 0)
                AS rider_payout,
              COALESCE(SUM(CASE WHEN entry_type IN ('TCS', 'TCS_COLLECTED')
                THEN credit_paise ELSE 0 END), 0) AS tcs,
              COALESCE(SUM(CASE WHEN entry_type = 'ORDER_GMV' THEN 1 ELSE 0 END), 0) AS orders
            FROM financial_ledger
            WHERE created_at >= ? AND created_at < ?
            """,
            (rs, i) ->
                new PeriodTotals(
                    rs.getLong("gmv"),
                    rs.getLong("commission"),
                    rs.getLong("refunds"),
                    rs.getLong("gateway_fees"),
                    rs.getLong("pharmacy_payout"),
                    rs.getLong("rider_payout"),
                    rs.getLong("tcs"),
                    rs.getLong("orders"),
                    0L,
                    0L),
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    PeriodTotals base =
        ledger == null || ledger.isEmpty()
            ? new PeriodTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            : ledger.getFirst();

    Long codOrders =
        jdbc.queryForObject(
            """
            SELECT COUNT(1) FROM payment
            WHERE status = 'CAPTURED'
              AND method = 'COD'
              AND created_at >= ? AND created_at < ?
            """,
            Long.class,
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    long cod = codOrders == null ? 0L : codOrders;

    Long paymentOrders =
        jdbc.queryForObject(
            """
            SELECT COUNT(1) FROM payment
            WHERE status = 'CAPTURED'
              AND created_at >= ? AND created_at < ?
            """,
            Long.class,
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    long ordersFromPayments = paymentOrders == null ? 0L : paymentOrders;
    long orders = base.ordersCount() > 0 ? base.ordersCount() : ordersFromPayments;

    return new PeriodTotals(
        base.gmvPaise(),
        base.commissionPaise(),
        base.refundsPaise(),
        base.gatewayFeesPaise(),
        base.pharmacyPayoutPaise(),
        base.riderPayoutPaise(),
        base.tcsPaise(),
        orders,
        cod,
        ordersFromPayments);
  }

  @Override
  public List<ChartPoint> gmvChart(
      Instant fromInclusive, Instant toExclusive, ChartGranularity granularity) {
    String sql =
        granularity == ChartGranularity.HOURLY
            ? """
              SELECT
                to_char(
                  date_trunc('hour', created_at AT TIME ZONE 'Asia/Kolkata'),
                  'YYYY-MM-DD"T"HH24:00:00'
                ) AS label,
                COALESCE(SUM(credit_paise), 0) AS gmv,
                COUNT(1) AS orders
              FROM financial_ledger
              WHERE entry_type = 'ORDER_GMV'
                AND created_at >= ? AND created_at < ?
              GROUP BY 1
              ORDER BY 1
              """
            : """
              SELECT
                to_char(
                  date_trunc('day', created_at AT TIME ZONE 'Asia/Kolkata'),
                  'YYYY-MM-DD'
                ) AS label,
                COALESCE(SUM(credit_paise), 0) AS gmv,
                COUNT(1) AS orders
              FROM financial_ledger
              WHERE entry_type = 'ORDER_GMV'
                AND created_at >= ? AND created_at < ?
              GROUP BY 1
              ORDER BY 1
              """;
    List<ChartPoint> points =
        jdbc.query(
            sql,
            (rs, i) ->
                new ChartPoint(rs.getString("label"), rs.getLong("gmv"), rs.getLong("orders")),
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    return new ArrayList<>(points);
  }

  @Override
  public long gmvSum(Instant fromInclusive, Instant toExclusive) {
    Long sum =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(credit_paise), 0) FROM financial_ledger
            WHERE entry_type = 'ORDER_GMV'
              AND created_at >= ? AND created_at < ?
            """,
            Long.class,
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    return sum == null ? 0L : sum;
  }
}

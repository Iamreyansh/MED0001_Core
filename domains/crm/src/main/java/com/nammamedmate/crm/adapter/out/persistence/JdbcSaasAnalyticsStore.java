package com.nammamedmate.crm.adapter.out.persistence;

import com.nammamedmate.crm.application.port.out.SaasAnalyticsStore;
import com.nammamedmate.crm.domain.AnalyticsMath;
import com.nammamedmate.crm.domain.SaasMetricsSnapshot;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcSaasAnalyticsStore implements SaasAnalyticsStore {

  private final JdbcTemplate jdbc;

  public JdbcSaasAnalyticsStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<SaasMetricsSnapshot> findMetrics(LocalDate metricMonth) {
    List<SaasMetricsSnapshot> rows =
        jdbc.query(
            """
            SELECT metric_month, mrr_paise, arr_paise, arpa_paise, nrr_pct, grr_pct,
                   quick_ratio, magic_number, ltv_paise, cac_paise, logo_churn_pct,
                   start_mrr_paise, new_mrr_paise, expansion_mrr_paise, contraction_mrr_paise,
                   churn_mrr_paise, net_new_mrr_paise, new_logos, churned_logos,
                   expansion_accounts, contraction_accounts, computed_at
            FROM saas_metrics_cache
            WHERE metric_month = ?
            """,
            (rs, i) ->
                new SaasMetricsSnapshot(
                    rs.getDate("metric_month").toLocalDate(),
                    rs.getLong("mrr_paise"),
                    rs.getLong("arr_paise"),
                    rs.getLong("arpa_paise"),
                    rs.getBigDecimal("nrr_pct"),
                    rs.getBigDecimal("grr_pct"),
                    rs.getBigDecimal("quick_ratio"),
                    rs.getBigDecimal("magic_number"),
                    rs.getLong("ltv_paise"),
                    rs.getLong("cac_paise"),
                    rs.getBigDecimal("logo_churn_pct"),
                    rs.getLong("start_mrr_paise"),
                    rs.getLong("new_mrr_paise"),
                    rs.getLong("expansion_mrr_paise"),
                    rs.getLong("contraction_mrr_paise"),
                    rs.getLong("churn_mrr_paise"),
                    rs.getLong("net_new_mrr_paise"),
                    rs.getInt("new_logos"),
                    rs.getInt("churned_logos"),
                    rs.getInt("expansion_accounts"),
                    rs.getInt("contraction_accounts"),
                    rs.getTimestamp("computed_at").toInstant()),
            Date.valueOf(metricMonth));
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public void upsertMetrics(SaasMetricsSnapshot s) {
    jdbc.update(
        """
        INSERT INTO saas_metrics_cache (
          metric_month, mrr_paise, arr_paise, arpa_paise, nrr_pct, grr_pct, quick_ratio,
          magic_number, ltv_paise, cac_paise, logo_churn_pct, start_mrr_paise, new_mrr_paise,
          expansion_mrr_paise, contraction_mrr_paise, churn_mrr_paise, net_new_mrr_paise,
          new_logos, churned_logos, expansion_accounts, contraction_accounts, computed_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT (metric_month) DO UPDATE SET
          mrr_paise = EXCLUDED.mrr_paise,
          arr_paise = EXCLUDED.arr_paise,
          arpa_paise = EXCLUDED.arpa_paise,
          nrr_pct = EXCLUDED.nrr_pct,
          grr_pct = EXCLUDED.grr_pct,
          quick_ratio = EXCLUDED.quick_ratio,
          magic_number = EXCLUDED.magic_number,
          ltv_paise = EXCLUDED.ltv_paise,
          cac_paise = EXCLUDED.cac_paise,
          logo_churn_pct = EXCLUDED.logo_churn_pct,
          start_mrr_paise = EXCLUDED.start_mrr_paise,
          new_mrr_paise = EXCLUDED.new_mrr_paise,
          expansion_mrr_paise = EXCLUDED.expansion_mrr_paise,
          contraction_mrr_paise = EXCLUDED.contraction_mrr_paise,
          churn_mrr_paise = EXCLUDED.churn_mrr_paise,
          net_new_mrr_paise = EXCLUDED.net_new_mrr_paise,
          new_logos = EXCLUDED.new_logos,
          churned_logos = EXCLUDED.churned_logos,
          expansion_accounts = EXCLUDED.expansion_accounts,
          contraction_accounts = EXCLUDED.contraction_accounts,
          computed_at = EXCLUDED.computed_at
        """,
        Date.valueOf(s.metricMonth()),
        s.mrrPaise(),
        s.arrPaise(),
        s.arpaPaise(),
        s.nrrPct(),
        s.grrPct(),
        s.quickRatio(),
        s.magicNumber(),
        s.ltvPaise(),
        s.cacPaise(),
        s.logoChurnPct(),
        s.startMrrPaise(),
        s.newMrrPaise(),
        s.expansionMrrPaise(),
        s.contractionMrrPaise(),
        s.churnMrrPaise(),
        s.netNewMrrPaise(),
        s.newLogos(),
        s.churnedLogos(),
        s.expansionAccounts(),
        s.contractionAccounts(),
        Timestamp.from(s.computedAt()));
  }

  @Override
  public List<SaasMetricsSnapshot> listMetrics(LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbc.query(
        """
        SELECT metric_month, mrr_paise, arr_paise, arpa_paise, nrr_pct, grr_pct,
               quick_ratio, magic_number, ltv_paise, cac_paise, logo_churn_pct,
               start_mrr_paise, new_mrr_paise, expansion_mrr_paise, contraction_mrr_paise,
               churn_mrr_paise, net_new_mrr_paise, new_logos, churned_logos,
               expansion_accounts, contraction_accounts, computed_at
        FROM saas_metrics_cache
        WHERE metric_month >= ? AND metric_month <= ?
        ORDER BY metric_month ASC
        """,
        (rs, i) ->
            new SaasMetricsSnapshot(
                rs.getDate("metric_month").toLocalDate(),
                rs.getLong("mrr_paise"),
                rs.getLong("arr_paise"),
                rs.getLong("arpa_paise"),
                rs.getBigDecimal("nrr_pct"),
                rs.getBigDecimal("grr_pct"),
                rs.getBigDecimal("quick_ratio"),
                rs.getBigDecimal("magic_number"),
                rs.getLong("ltv_paise"),
                rs.getLong("cac_paise"),
                rs.getBigDecimal("logo_churn_pct"),
                rs.getLong("start_mrr_paise"),
                rs.getLong("new_mrr_paise"),
                rs.getLong("expansion_mrr_paise"),
                rs.getLong("contraction_mrr_paise"),
                rs.getLong("churn_mrr_paise"),
                rs.getLong("net_new_mrr_paise"),
                rs.getInt("new_logos"),
                rs.getInt("churned_logos"),
                rs.getInt("expansion_accounts"),
                rs.getInt("contraction_accounts"),
                rs.getTimestamp("computed_at").toInstant()),
        Date.valueOf(fromInclusive),
        Date.valueOf(toInclusive));
  }

  @Override
  public long sumActiveMrrPaise(String planNameOrNull) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT COALESCE(SUM(
              COALESCE(p.price_monthly_paise, 0) + COALESCE(addons.addon_mrr, 0)
            ), 0)
            FROM saas_subscription s
            JOIN saas_plan p ON p.id = s.plan_id AND p.deleted_at IS NULL
            LEFT JOIN (
              SELECT aa.account_id, SUM(ad.price_monthly_paise) AS addon_mrr
              FROM crm_account_addon aa
              JOIN saas_addon ad ON ad.id = aa.addon_id AND ad.deleted_at IS NULL
              WHERE aa.detached_at IS NULL
              GROUP BY aa.account_id
            ) addons ON addons.account_id = s.account_id
            WHERE s.deleted_at IS NULL
              AND s.status IN ('ACTIVE', 'TRIAL')
            """);
    List<Object> args = new ArrayList<>();
    if (planNameOrNull != null && !planNameOrNull.isBlank()) {
      sql.append(" AND p.name = ?");
      args.add(planNameOrNull.trim().toUpperCase());
    }
    Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return n == null ? 0L : n;
  }

  @Override
  public long countPayingAccounts(String planNameOrNull) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT COUNT(*)
            FROM saas_subscription s
            JOIN saas_plan p ON p.id = s.plan_id AND p.deleted_at IS NULL
            WHERE s.deleted_at IS NULL
              AND s.status IN ('ACTIVE', 'TRIAL')
              AND (p.price_monthly_paise > 0 OR EXISTS (
                SELECT 1 FROM crm_account_addon aa
                WHERE aa.account_id = s.account_id AND aa.detached_at IS NULL
              ))
            """);
    List<Object> args = new ArrayList<>();
    if (planNameOrNull != null && !planNameOrNull.isBlank()) {
      sql.append(" AND p.name = ?");
      args.add(planNameOrNull.trim().toUpperCase());
    }
    Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return n == null ? 0L : n;
  }

  @Override
  public List<PlanMrrRow> mrrByPlan(String planNameOrNull) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT p.name AS plan,
                   COALESCE(SUM(
                     COALESCE(p.price_monthly_paise, 0) + COALESCE(addons.addon_mrr, 0)
                   ), 0) AS mrr_paise,
                   COUNT(*) AS account_count
            FROM saas_subscription s
            JOIN saas_plan p ON p.id = s.plan_id AND p.deleted_at IS NULL
            LEFT JOIN (
              SELECT aa.account_id, SUM(ad.price_monthly_paise) AS addon_mrr
              FROM crm_account_addon aa
              JOIN saas_addon ad ON ad.id = aa.addon_id AND ad.deleted_at IS NULL
              WHERE aa.detached_at IS NULL
              GROUP BY aa.account_id
            ) addons ON addons.account_id = s.account_id
            WHERE s.deleted_at IS NULL
              AND s.status IN ('ACTIVE', 'TRIAL')
            """);
    List<Object> args = new ArrayList<>();
    if (planNameOrNull != null && !planNameOrNull.isBlank()) {
      sql.append(" AND p.name = ?");
      args.add(planNameOrNull.trim().toUpperCase());
    }
    sql.append(" GROUP BY p.name ORDER BY mrr_paise DESC, p.name ASC");
    return jdbc.query(
        sql.toString(),
        (rs, i) ->
            new PlanMrrRow(
                rs.getString("plan"), rs.getLong("mrr_paise"), rs.getLong("account_count")),
        args.toArray());
  }

  @Override
  public long sumNewLogoMrrPaise(LocalDate monthStart, LocalDate monthEndExclusive) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(
              COALESCE(p.price_monthly_paise, 0) + COALESCE(addons.addon_mrr, 0)
            ), 0)
            FROM saas_subscription_cohort c
            JOIN saas_subscription s ON s.account_id = c.account_id AND s.deleted_at IS NULL
            JOIN saas_plan p ON p.id = s.plan_id AND p.deleted_at IS NULL
            LEFT JOIN (
              SELECT aa.account_id, SUM(ad.price_monthly_paise) AS addon_mrr
              FROM crm_account_addon aa
              JOIN saas_addon ad ON ad.id = aa.addon_id AND ad.deleted_at IS NULL
              WHERE aa.detached_at IS NULL
              GROUP BY aa.account_id
            ) addons ON addons.account_id = c.account_id
            WHERE c.cohort_month >= ? AND c.cohort_month < ?
              AND s.status IN ('ACTIVE', 'TRIAL')
            """,
            Long.class,
            Date.valueOf(monthStart),
            Date.valueOf(monthEndExclusive));
    return n == null ? 0L : n;
  }

  @Override
  public int countNewLogos(LocalDate monthStart, LocalDate monthEndExclusive) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM saas_subscription_cohort c
            WHERE c.cohort_month >= ? AND c.cohort_month < ?
            """,
            Integer.class,
            Date.valueOf(monthStart),
            Date.valueOf(monthEndExclusive));
    return n == null ? 0 : n;
  }

  @Override
  public long sumChurnMrrPaise(Instant periodStart, Instant periodEnd) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(COALESCE(pl.price_monthly_paise, 0)), 0)
            FROM saas_subscription s
            LEFT JOIN LATERAL (
              SELECT i.plan_name
              FROM saas_invoice i
              WHERE i.account_id = s.account_id AND i.deleted_at IS NULL
                AND i.plan_name <> 'FREE'
              ORDER BY i.created_at DESC
              LIMIT 1
            ) last_inv ON TRUE
            LEFT JOIN saas_plan pl ON pl.name = last_inv.plan_name AND pl.deleted_at IS NULL
            WHERE s.deleted_at IS NULL
              AND s.status IN ('EXPIRED', 'CANCELLED')
              AND COALESCE(s.expires_at, s.cancelled_at) >= ?
              AND COALESCE(s.expires_at, s.cancelled_at) < ?
            """,
            Long.class,
            Timestamp.from(periodStart),
            Timestamp.from(periodEnd));
    return n == null ? 0L : n;
  }

  @Override
  public int countChurnedLogos(Instant periodStart, Instant periodEnd) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM saas_subscription s
            WHERE s.deleted_at IS NULL
              AND s.status IN ('EXPIRED', 'CANCELLED')
              AND COALESCE(s.expires_at, s.cancelled_at) >= ?
              AND COALESCE(s.expires_at, s.cancelled_at) < ?
            """,
            Integer.class,
            Timestamp.from(periodStart),
            Timestamp.from(periodEnd));
    return n == null ? 0 : n;
  }

  @Override
  public long sumExpansionMrrPaise(Instant periodStart, Instant periodEnd) {
    // Addon attaches this period + plan upgrade invoice PLAN lines vs prior plan (ponytail:
    // addons).
    Long n =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(ad.price_monthly_paise), 0)
            FROM crm_account_addon aa
            JOIN saas_addon ad ON ad.id = aa.addon_id AND ad.deleted_at IS NULL
            WHERE aa.effective_from >= ? AND aa.effective_from < ?
              AND (aa.detached_at IS NULL OR aa.detached_at >= ?)
            """,
            Long.class,
            Timestamp.from(periodStart),
            Timestamp.from(periodEnd),
            Timestamp.from(periodStart));
    return n == null ? 0L : n;
  }

  @Override
  public int countExpansionAccounts(Instant periodStart, Instant periodEnd) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(DISTINCT aa.account_id)
            FROM crm_account_addon aa
            WHERE aa.effective_from >= ? AND aa.effective_from < ?
              AND (aa.detached_at IS NULL OR aa.detached_at >= ?)
            """,
            Integer.class,
            Timestamp.from(periodStart),
            Timestamp.from(periodEnd),
            Timestamp.from(periodStart));
    return n == null ? 0 : n;
  }

  @Override
  public long sumContractionMrrPaise(Instant periodStart, Instant periodEnd) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(ad.price_monthly_paise), 0)
            FROM crm_account_addon aa
            JOIN saas_addon ad ON ad.id = aa.addon_id AND ad.deleted_at IS NULL
            WHERE aa.detached_at >= ? AND aa.detached_at < ?
            """,
            Long.class,
            Timestamp.from(periodStart),
            Timestamp.from(periodEnd));
    return n == null ? 0L : n;
  }

  @Override
  public int countContractionAccounts(Instant periodStart, Instant periodEnd) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(DISTINCT aa.account_id)
            FROM crm_account_addon aa
            WHERE aa.detached_at >= ? AND aa.detached_at < ?
            """,
            Integer.class,
            Timestamp.from(periodStart),
            Timestamp.from(periodEnd));
    return n == null ? 0 : n;
  }

  @Override
  public long smSpendPaise(LocalDate monthStart) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT amount_paise FROM saas_sm_spend WHERE period_month = ?
            """,
            Long.class,
            Date.valueOf(monthStart));
    return n == null ? 0L : n;
  }

  @Override
  public long sumSmSpendPaise(LocalDate fromInclusive, LocalDate toInclusive) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(amount_paise), 0)
            FROM saas_sm_spend
            WHERE period_month >= ? AND period_month <= ?
            """,
            Long.class,
            Date.valueOf(fromInclusive),
            Date.valueOf(toInclusive));
    return n == null ? 0L : n;
  }

  @Override
  public void replaceCohortRetention(List<CohortRetentionRow> rows) {
    jdbc.update("DELETE FROM saas_cohort_retention");
    for (CohortRetentionRow r : rows) {
      jdbc.update(
          """
          INSERT INTO saas_cohort_retention (
            cohort_month, months_since, starting_accounts, retained_accounts, retention_pct
          ) VALUES (?, ?, ?, ?, ?)
          """,
          Date.valueOf(r.cohortMonth()),
          r.monthsSince(),
          r.startingAccounts(),
          r.retainedAccounts(),
          r.retentionPct());
    }
  }

  @Override
  public List<CohortRetentionRow> listCohortRetention(
      LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbc.query(
        """
        SELECT cohort_month, months_since, starting_accounts, retained_accounts, retention_pct
        FROM saas_cohort_retention
        WHERE cohort_month >= ? AND cohort_month <= ?
        ORDER BY cohort_month ASC, months_since ASC
        """,
        (rs, i) ->
            new CohortRetentionRow(
                rs.getDate("cohort_month").toLocalDate(),
                rs.getInt("months_since"),
                rs.getInt("starting_accounts"),
                rs.getInt("retained_accounts"),
                rs.getBigDecimal("retention_pct")),
        Date.valueOf(fromInclusive),
        Date.valueOf(toInclusive));
  }

  @Override
  public List<CohortRetentionRow> computeLiveCohortRetention(
      LocalDate cohortFrom, LocalDate cohortTo, LocalDate asOfMonth) {
    List<LocalDate> cohorts =
        jdbc.query(
            """
            SELECT DISTINCT cohort_month
            FROM saas_subscription_cohort
            WHERE cohort_month >= ? AND cohort_month <= ?
            ORDER BY cohort_month ASC
            """,
            (rs, i) -> rs.getDate("cohort_month").toLocalDate(),
            Date.valueOf(cohortFrom),
            Date.valueOf(cohortTo));
    List<CohortRetentionRow> out = new ArrayList<>();
    for (LocalDate cohort : cohorts) {
      Integer starting =
          jdbc.queryForObject(
              """
              SELECT COUNT(*) FROM saas_subscription_cohort WHERE cohort_month = ?
              """,
              Integer.class,
              Date.valueOf(cohort));
      int start = starting == null ? 0 : starting;
      int maxMonths =
          (int)
              Math.max(
                  0,
                  (asOfMonth.getYear() - cohort.getYear()) * 12
                      + (asOfMonth.getMonthValue() - cohort.getMonthValue()));
      for (int m = 0; m <= maxMonths && m <= 12; m++) {
        LocalDate cutoff = cohort.plusMonths(m + 1L);
        Integer retained =
            jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM saas_subscription_cohort c
                JOIN saas_subscription s ON s.account_id = c.account_id AND s.deleted_at IS NULL
                WHERE c.cohort_month = ?
                  AND (
                    s.status IN ('ACTIVE', 'TRIAL', 'PAST_DUE')
                    OR COALESCE(s.expires_at, s.cancelled_at) >= ?
                  )
                """,
                Integer.class,
                Date.valueOf(cohort),
                Timestamp.from(cutoff.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
        int ret = retained == null ? 0 : retained;
        if (m == 0) {
          ret = start;
        }
        out.add(
            new CohortRetentionRow(cohort, m, start, ret, AnalyticsMath.retentionPct(ret, start)));
      }
    }
    return out;
  }
}

package com.nammamedmate.crm.adapter.out.persistence;

import com.nammamedmate.crm.application.port.out.SaasRenewalChurnStore;
import com.nammamedmate.crm.domain.ChurnSurvey;
import com.nammamedmate.crm.domain.HealthBand;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.crm.domain.RenewalRiskLevel;
import com.nammamedmate.crm.domain.SubscriptionStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcSaasRenewalChurnStore implements SaasRenewalChurnStore {

  private final JdbcTemplate jdbc;

  public JdbcSaasRenewalChurnStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void ensureCohort(UUID accountId, LocalDate cohortMonth, Instant now) {
    jdbc.update(
        """
        INSERT INTO saas_subscription_cohort (account_id, cohort_month, created_at)
        VALUES (?, ?, ?)
        ON CONFLICT (account_id) DO NOTHING
        """,
        accountId,
        Date.valueOf(cohortMonth),
        Timestamp.from(now));
  }

  @Override
  public ChurnSurvey insertSurvey(ChurnSurvey survey) {
    jdbc.update(
        """
        INSERT INTO crm_churn_survey (id, account_id, reason, notes, logged_by, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        survey.id(),
        survey.accountId(),
        survey.reason(),
        survey.notes(),
        survey.loggedBy(),
        Timestamp.from(survey.createdAt()));
    return survey;
  }

  @Override
  public List<UpcomingRow> listUpcoming(
      Instant now, Instant windowEnd, String riskLevel, UUID csmId, int offset, int limit) {
    StringBuilder sql = new StringBuilder(upcomingSelect());
    sql.append(" WHERE s.deleted_at IS NULL AND a.deleted_at IS NULL");
    sql.append(" AND s.status IN ('ACTIVE', 'TRIAL', 'PAST_DUE')");
    sql.append(" AND s.renewal_date >= ? AND s.renewal_date <= ?");
    sql.append(" AND COALESCE(p.name, a.current_plan_name) <> 'FREE'");
    List<Object> args = new ArrayList<>();
    args.add(Timestamp.from(now));
    args.add(Timestamp.from(windowEnd));
    appendRiskFilter(sql, args, riskLevel);
    if (csmId != null) {
      // ponytail: CSM assignment not modelled yet — filter matches nothing.
      sql.append(" AND 1 = 0");
    }
    sql.append(" ORDER BY s.renewal_date ASC, COALESCE(p.price_monthly_paise, 0) DESC");
    sql.append(" OFFSET ? LIMIT ?");
    args.add(offset);
    args.add(limit);
    return jdbc.query(sql.toString(), this::mapUpcoming, args.toArray());
  }

  @Override
  public long countUpcoming(Instant now, Instant windowEnd, String riskLevel, UUID csmId) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT COUNT(*)
            FROM saas_subscription s
            JOIN crm_account a ON a.id = s.account_id AND a.deleted_at IS NULL
            LEFT JOIN saas_plan p ON p.id = s.plan_id AND p.deleted_at IS NULL
            LEFT JOIN crm_account_health_score h ON h.account_id = a.id
            WHERE s.deleted_at IS NULL
              AND s.status IN ('ACTIVE', 'TRIAL', 'PAST_DUE')
              AND s.renewal_date >= ? AND s.renewal_date <= ?
              AND COALESCE(p.name, a.current_plan_name) <> 'FREE'
            """);
    List<Object> args = new ArrayList<>();
    args.add(Timestamp.from(now));
    args.add(Timestamp.from(windowEnd));
    appendRiskFilter(sql, args, riskLevel);
    if (csmId != null) {
      sql.append(" AND 1 = 0");
    }
    Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return n == null ? 0L : n;
  }

  @Override
  public long countRenewing(Instant now, Instant windowEnd) {
    return countUpcoming(now, windowEnd, null, null);
  }

  @Override
  public long sumMrrAtRiskPaise(Instant now, Instant windowEnd) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(COALESCE(p.price_monthly_paise, 0)), 0)
            FROM saas_subscription s
            JOIN crm_account a ON a.id = s.account_id AND a.deleted_at IS NULL
            LEFT JOIN saas_plan p ON p.id = s.plan_id AND p.deleted_at IS NULL
            LEFT JOIN crm_account_health_score h ON h.account_id = a.id
            WHERE s.deleted_at IS NULL
              AND s.status IN ('ACTIVE', 'TRIAL', 'PAST_DUE')
              AND s.renewal_date >= ? AND s.renewal_date <= ?
              AND COALESCE(p.name, a.current_plan_name) <> 'FREE'
              AND COALESCE(h.overall_score, 0) < ?
            """,
            Long.class,
            Timestamp.from(now),
            Timestamp.from(windowEnd),
            HealthBand.AT_RISK_THRESHOLD);
    return n == null ? 0L : n;
  }

  @Override
  public long countChurnedLogos(Instant periodStart, Instant periodEnd) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM saas_subscription s
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
  public long countStartOfPeriodLogos(Instant periodStart, Instant periodEnd) {
    long churned = countChurnedLogos(periodStart, periodEnd);
    Long active =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM saas_subscription s
            JOIN saas_plan p ON p.id = s.plan_id AND p.deleted_at IS NULL
            WHERE s.deleted_at IS NULL
              AND s.status IN ('ACTIVE', 'TRIAL', 'PAST_DUE')
              AND p.name <> 'FREE'
            """,
            Long.class);
    return (active == null ? 0L : active) + churned;
  }

  @Override
  public long sumMrrChurnedPaise(Instant periodStart, Instant periodEnd) {
    // Denorm plan is FREE after churn — MRR from last paid invoice plan.
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
  public long countSavePlaysSince(Instant since) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(DISTINCT account_id)
            FROM crm_save_play
            WHERE created_at >= ?
            """,
            Long.class,
            Timestamp.from(since));
    return n == null ? 0L : n;
  }

  @Override
  public List<ReasonCount> churnReasons(Instant periodStart, Instant periodEnd) {
    return jdbc.query(
        """
        SELECT reason, COUNT(*) AS cnt
        FROM crm_churn_survey
        WHERE created_at >= ? AND created_at < ?
        GROUP BY reason
        ORDER BY cnt DESC, reason ASC
        """,
        (rs, i) -> new ReasonCount(rs.getString("reason"), rs.getLong("cnt")),
        Timestamp.from(periodStart),
        Timestamp.from(periodEnd));
  }

  @Override
  public List<ChurnLogRow> churnLog(Instant periodStart, Instant periodEnd, int limit) {
    return jdbc.query(
        """
        SELECT a.id AS account_id,
               COALESCE(ph.name, 'Pharmacy') AS pharmacy_name,
               COALESCE(last_inv.plan_name, a.current_plan_name) AS plan,
               COALESCE(pl.price_monthly_paise, 0) AS mrr_paise,
               COALESCE(s.expires_at, s.cancelled_at) AS churned_at,
               cs.reason
        FROM saas_subscription s
        JOIN crm_account a ON a.id = s.account_id AND a.deleted_at IS NULL
        LEFT JOIN pharmacies ph ON ph.id = a.pharmacy_id AND ph.deleted_at IS NULL
        LEFT JOIN LATERAL (
          SELECT i.plan_name
          FROM saas_invoice i
          WHERE i.account_id = a.id AND i.deleted_at IS NULL AND i.plan_name <> 'FREE'
          ORDER BY i.created_at DESC
          LIMIT 1
        ) last_inv ON TRUE
        LEFT JOIN saas_plan pl ON pl.name = COALESCE(last_inv.plan_name, a.current_plan_name)
          AND pl.deleted_at IS NULL
        LEFT JOIN LATERAL (
          SELECT reason FROM crm_churn_survey
          WHERE account_id = a.id
          ORDER BY created_at DESC
          LIMIT 1
        ) cs ON TRUE
        WHERE s.deleted_at IS NULL
          AND s.status IN ('EXPIRED', 'CANCELLED')
          AND COALESCE(s.expires_at, s.cancelled_at) >= ?
          AND COALESCE(s.expires_at, s.cancelled_at) < ?
        ORDER BY COALESCE(s.expires_at, s.cancelled_at) DESC
        LIMIT ?
        """,
        (rs, i) ->
            new ChurnLogRow(
                (UUID) rs.getObject("account_id"),
                rs.getString("pharmacy_name"),
                rs.getString("plan"),
                rs.getLong("mrr_paise"),
                ts(rs, "churned_at"),
                rs.getString("reason")),
        Timestamp.from(periodStart),
        Timestamp.from(periodEnd),
        limit);
  }

  @Override
  public List<CohortRate> cohortChurnRates(LocalDate asOf) {
    return jdbc.query(
        """
        SELECT c.cohort_month,
               COUNT(*) AS cohort_size,
               COUNT(*) FILTER (
                 WHERE s.status IN ('EXPIRED', 'CANCELLED')
                   AND COALESCE(s.expires_at, s.cancelled_at)
                       < (c.cohort_month + INTERVAL '1 month')
               ) AS m1,
               COUNT(*) FILTER (
                 WHERE s.status IN ('EXPIRED', 'CANCELLED')
                   AND COALESCE(s.expires_at, s.cancelled_at)
                       < (c.cohort_month + INTERVAL '3 months')
               ) AS m3,
               COUNT(*) FILTER (
                 WHERE s.status IN ('EXPIRED', 'CANCELLED')
                   AND COALESCE(s.expires_at, s.cancelled_at)
                       < (c.cohort_month + INTERVAL '6 months')
               ) AS m6
        FROM saas_subscription_cohort c
        LEFT JOIN saas_subscription s ON s.account_id = c.account_id AND s.deleted_at IS NULL
        GROUP BY c.cohort_month
        ORDER BY c.cohort_month DESC
        LIMIT 24
        """,
        (rs, i) -> {
          LocalDate cohort = rs.getDate("cohort_month").toLocalDate();
          long size = rs.getLong("cohort_size");
          return new CohortRate(
              cohort.toString().substring(0, 7),
              rateOrNull(rs.getLong("m1"), size, cohort, asOf, 1),
              rateOrNull(rs.getLong("m3"), size, cohort, asOf, 3),
              rateOrNull(rs.getLong("m6"), size, cohort, asOf, 6));
        });
  }

  @Override
  public long countChurnedWithLowAdoption(Instant periodStart, Instant periodEnd) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(DISTINCT s.account_id)
            FROM saas_subscription s
            JOIN crm_account_health_score h ON h.account_id = s.account_id
            WHERE s.deleted_at IS NULL
              AND s.status IN ('EXPIRED', 'CANCELLED')
              AND COALESCE(s.expires_at, s.cancelled_at) >= ?
              AND COALESCE(s.expires_at, s.cancelled_at) < ?
              AND h.product_usage_score < 20
            """,
            Long.class,
            Timestamp.from(periodStart),
            Timestamp.from(periodEnd));
    return n == null ? 0L : n;
  }

  @Override
  public long countChurnedWithMissedPayments(Instant periodStart, Instant periodEnd) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(DISTINCT s.account_id)
            FROM saas_subscription s
            WHERE s.deleted_at IS NULL
              AND s.status IN ('EXPIRED', 'CANCELLED')
              AND COALESCE(s.expires_at, s.cancelled_at) >= ?
              AND COALESCE(s.expires_at, s.cancelled_at) < ?
              AND (
                s.past_due_at IS NOT NULL
                OR EXISTS (
                  SELECT 1 FROM saas_invoice i
                  WHERE i.account_id = s.account_id
                    AND i.deleted_at IS NULL
                    AND i.status IN ('OVERDUE', 'DUE')
                )
              )
            """,
            Long.class,
            Timestamp.from(periodStart),
            Timestamp.from(periodEnd));
    return n == null ? 0L : n;
  }

  @Override
  public List<UUID> findWinbackDue(Instant dayStart, Instant dayEnd) {
    return jdbc.query(
        """
        SELECT s.account_id
        FROM saas_subscription s
        WHERE s.deleted_at IS NULL
          AND s.status = ?
          AND s.expires_at >= ?
          AND s.expires_at < ?
        """,
        (rs, i) -> (UUID) rs.getObject("account_id"),
        SubscriptionStatus.EXPIRED,
        Timestamp.from(dayStart),
        Timestamp.from(dayEnd));
  }

  @Override
  public List<AtRiskAlertRow> findAtRiskRenewals(Instant now, Instant windowEnd) {
    return jdbc.query(
        """
        SELECT a.id AS account_id, s.id AS subscription_id, h.overall_score
        FROM saas_subscription s
        JOIN crm_account a ON a.id = s.account_id AND a.deleted_at IS NULL
        JOIN crm_account_health_score h ON h.account_id = a.id
        LEFT JOIN saas_plan p ON p.id = s.plan_id AND p.deleted_at IS NULL
        WHERE s.deleted_at IS NULL
          AND s.status IN ('ACTIVE', 'TRIAL', 'PAST_DUE')
          AND s.renewal_date > ?
          AND s.renewal_date <= ?
          AND h.overall_score < ?
          AND COALESCE(p.name, a.current_plan_name) <> ?
        """,
        (rs, i) ->
            new AtRiskAlertRow(
                (UUID) rs.getObject("account_id"),
                (UUID) rs.getObject("subscription_id"),
                rs.getDouble("overall_score")),
        Timestamp.from(now),
        Timestamp.from(windowEnd),
        HealthBand.AT_RISK_THRESHOLD,
        PlanNames.FREE);
  }

  private static String upcomingSelect() {
    return """
        SELECT a.id AS account_id,
               COALESCE(ph.name, 'Pharmacy') AS pharmacy_name,
               COALESCE(p.name, a.current_plan_name) AS plan,
               COALESCE(p.price_monthly_paise, 0) AS mrr_paise,
               CAST(s.renewal_date AS DATE) AS renewal_date,
               s.auto_renew,
               COALESCE(h.overall_score, 0) AS health_score,
               (SELECT MAX(sp.created_at) FROM crm_save_play sp WHERE sp.account_id = a.id)
                 AS last_save_play_at
        FROM saas_subscription s
        JOIN crm_account a ON a.id = s.account_id
        LEFT JOIN pharmacies ph ON ph.id = a.pharmacy_id AND ph.deleted_at IS NULL
        LEFT JOIN saas_plan p ON p.id = s.plan_id AND p.deleted_at IS NULL
        LEFT JOIN crm_account_health_score h ON h.account_id = a.id
        """;
  }

  private static void appendRiskFilter(StringBuilder sql, List<Object> args, String riskLevel) {
    if (riskLevel == null) {
      return;
    }
    if (RenewalRiskLevel.LOW.equals(riskLevel)) {
      sql.append(" AND COALESCE(h.overall_score, 0) >= 75");
    } else if (RenewalRiskLevel.MEDIUM.equals(riskLevel)) {
      sql.append(" AND COALESCE(h.overall_score, 0) >= 50 AND COALESCE(h.overall_score, 0) < 75");
    } else if (RenewalRiskLevel.HIGH.equals(riskLevel)) {
      sql.append(" AND COALESCE(h.overall_score, 0) < 50");
    }
  }

  private UpcomingRow mapUpcoming(ResultSet rs, int i) throws SQLException {
    Date renewal = rs.getDate("renewal_date");
    return new UpcomingRow(
        (UUID) rs.getObject("account_id"),
        rs.getString("pharmacy_name"),
        rs.getString("plan"),
        rs.getLong("mrr_paise"),
        renewal == null ? null : renewal.toLocalDate(),
        rs.getBoolean("auto_renew"),
        rs.getDouble("health_score"),
        ts(rs, "last_save_play_at"),
        null);
  }

  private static BigDecimal rateOrNull(
      long churned, long size, LocalDate cohort, LocalDate asOf, int months) {
    if (size <= 0) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    if (asOf.isBefore(cohort.plusMonths(months))) {
      return null;
    }
    return BigDecimal.valueOf(churned)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(size), 1, RoundingMode.HALF_UP);
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}

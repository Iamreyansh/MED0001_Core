package com.nammamedmate.crm.adapter.out.persistence;

import com.nammamedmate.crm.application.port.out.SaasAccountHealthStore;
import com.nammamedmate.crm.domain.AccountHealthScore;
import com.nammamedmate.crm.domain.AccountHealthSnapshot;
import com.nammamedmate.crm.domain.HealthBand;
import com.nammamedmate.crm.domain.SavePlay;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcSaasAccountHealthStore implements SaasAccountHealthStore {

  private final JdbcTemplate jdbc;

  public JdbcSaasAccountHealthStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AccountHealthScore> findByAccountId(UUID accountId) {
    List<AccountHealthScore> rows =
        jdbc.query(
            """
            SELECT id, account_id, overall_score, product_usage_score, billing_health_score,
                   support_satisfaction_score, business_performance_score, health_band,
                   risk_factors, recommended_actions, computed_at
            FROM crm_account_health_score WHERE account_id = ?
            """,
            this::mapScore,
            accountId);
    return rows.stream().findFirst();
  }

  @Override
  public void upsert(AccountHealthScore score) {
    jdbc.execute(
        (ConnectionCallback<Integer>)
            con -> {
              try (PreparedStatement ps =
                  con.prepareStatement(
                      """
                      INSERT INTO crm_account_health_score (
                        id, account_id, overall_score, product_usage_score, billing_health_score,
                        support_satisfaction_score, business_performance_score, health_band,
                        risk_factors, recommended_actions, computed_at, created_at, updated_at
                      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                      ON CONFLICT (account_id) DO UPDATE SET
                        overall_score = EXCLUDED.overall_score,
                        product_usage_score = EXCLUDED.product_usage_score,
                        billing_health_score = EXCLUDED.billing_health_score,
                        support_satisfaction_score = EXCLUDED.support_satisfaction_score,
                        business_performance_score = EXCLUDED.business_performance_score,
                        health_band = EXCLUDED.health_band,
                        risk_factors = EXCLUDED.risk_factors,
                        recommended_actions = EXCLUDED.recommended_actions,
                        computed_at = EXCLUDED.computed_at,
                        updated_at = EXCLUDED.updated_at
                      """)) {
                Timestamp now = Timestamp.from(score.computedAt());
                ps.setObject(1, score.id());
                ps.setObject(2, score.accountId());
                ps.setBigDecimal(3, bd(score.overallScore()));
                ps.setBigDecimal(4, bd(score.productUsageScore()));
                ps.setBigDecimal(5, bd(score.billingHealthScore()));
                ps.setBigDecimal(6, bd(score.supportSatisfactionScore()));
                ps.setBigDecimal(7, bd(score.businessPerformanceScore()));
                ps.setString(8, score.healthBand());
                ps.setArray(9, con.createArrayOf("text", toArray(score.riskFactors())));
                ps.setArray(10, con.createArrayOf("text", toArray(score.recommendedActions())));
                ps.setTimestamp(11, now);
                ps.setTimestamp(12, now);
                ps.setTimestamp(13, now);
                return ps.executeUpdate();
              }
            });
  }

  @Override
  public void upsertSnapshot(AccountHealthSnapshot snapshot) {
    jdbc.update(
        """
        INSERT INTO crm_account_health_snapshot (
          id, account_id, score_date, overall_score, health_band,
          product_usage_score, billing_health_score, support_satisfaction_score,
          business_performance_score, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
        ON CONFLICT (account_id, score_date) DO UPDATE SET
          overall_score = EXCLUDED.overall_score,
          health_band = EXCLUDED.health_band,
          product_usage_score = EXCLUDED.product_usage_score,
          billing_health_score = EXCLUDED.billing_health_score,
          support_satisfaction_score = EXCLUDED.support_satisfaction_score,
          business_performance_score = EXCLUDED.business_performance_score
        """,
        snapshot.id(),
        snapshot.accountId(),
        Date.valueOf(snapshot.scoreDate()),
        bd(snapshot.overallScore()),
        snapshot.healthBand(),
        bd(snapshot.productUsageScore()),
        bd(snapshot.billingHealthScore()),
        bd(snapshot.supportSatisfactionScore()),
        bd(snapshot.businessPerformanceScore()));
  }

  @Override
  public SavePlay insertSavePlay(SavePlay play) {
    jdbc.update(
        """
        INSERT INTO crm_save_play (id, account_id, action_type, outcome, notes, logged_by, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        play.id(),
        play.accountId(),
        play.actionType(),
        play.outcome(),
        play.notes(),
        play.loggedBy(),
        Timestamp.from(play.createdAt()));
    return play;
  }

  @Override
  public Instant maxSavePlayAt(UUID accountId) {
    return jdbc.query(
        "SELECT MAX(created_at) AS created_at FROM crm_save_play WHERE account_id = ?",
        rs -> rs.next() ? ts(rs, "created_at") : null,
        accountId);
  }

  @Override
  public long countOpenSavePlayAccounts() {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(DISTINCT sp.account_id)
            FROM crm_save_play sp
            JOIN crm_account_health_score h ON h.account_id = sp.account_id
            WHERE h.overall_score < ?
            """,
            Long.class,
            HealthBand.AT_RISK_THRESHOLD);
    return n == null ? 0L : n;
  }

  @Override
  public List<AtRiskRow> listAtRisk(String healthBand, int offset, int limit) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT h.account_id, COALESCE(ph.name, 'Pharmacy') AS pharmacy_name,
                   a.current_plan_name AS plan,
                   COALESCE(p.price_monthly_paise, 0) AS mrr_paise,
                   h.overall_score, h.health_band,
                   CAST(s.renewal_date AS DATE) AS renewal_date,
                   (SELECT MAX(sp.created_at) FROM crm_save_play sp WHERE sp.account_id = h.account_id)
                     AS last_save_play_at
            FROM crm_account_health_score h
            JOIN crm_account a ON a.id = h.account_id AND a.deleted_at IS NULL
            LEFT JOIN pharmacies ph ON ph.id = a.pharmacy_id AND ph.deleted_at IS NULL
            LEFT JOIN saas_plan p ON p.name = a.current_plan_name AND p.deleted_at IS NULL
            LEFT JOIN saas_subscription s ON s.account_id = a.id AND s.deleted_at IS NULL
            WHERE h.overall_score < ?
            """);
    if (healthBand != null) {
      sql.append(" AND h.health_band = ?");
    }
    sql.append(" ORDER BY COALESCE(p.price_monthly_paise, 0) DESC, h.overall_score ASC");
    sql.append(" OFFSET ? LIMIT ?");
    if (healthBand != null) {
      return jdbc.query(
          sql.toString(), this::mapAtRisk, HealthBand.AT_RISK_THRESHOLD, healthBand, offset, limit);
    }
    return jdbc.query(sql.toString(), this::mapAtRisk, HealthBand.AT_RISK_THRESHOLD, offset, limit);
  }

  @Override
  public long countAtRisk(String healthBand) {
    if (healthBand != null) {
      Long n =
          jdbc.queryForObject(
              """
              SELECT COUNT(*) FROM crm_account_health_score
              WHERE overall_score < ? AND health_band = ?
              """,
              Long.class,
              HealthBand.AT_RISK_THRESHOLD,
              healthBand);
      return n == null ? 0L : n;
    }
    Long n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM crm_account_health_score WHERE overall_score < ?",
            Long.class,
            HealthBand.AT_RISK_THRESHOLD);
    return n == null ? 0L : n;
  }

  @Override
  public long sumMrrAtRiskPaise() {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(COALESCE(p.price_monthly_paise, 0)), 0)
            FROM crm_account_health_score h
            JOIN crm_account a ON a.id = h.account_id AND a.deleted_at IS NULL
            LEFT JOIN saas_plan p ON p.name = a.current_plan_name AND p.deleted_at IS NULL
            WHERE h.overall_score < ?
            """,
            Long.class,
            HealthBand.AT_RISK_THRESHOLD);
    return n == null ? 0L : n;
  }

  @Override
  public HealthKpis kpis() {
    return jdbc.query(
        """
        SELECT
          COALESCE(AVG(overall_score), 0) AS avg_score,
          COALESCE(
            100.0 * COUNT(*) FILTER (WHERE health_band = 'HEALTHY') / NULLIF(COUNT(*), 0), 0
          ) AS healthy_pct,
          COALESCE(
            100.0 * COUNT(*) FILTER (WHERE health_band = 'MODERATE') / NULLIF(COUNT(*), 0), 0
          ) AS moderate_pct,
          COUNT(*) FILTER (WHERE health_band = 'AT_RISK') AS at_risk_count,
          COUNT(*) FILTER (WHERE health_band = 'CHURNING') AS churning_count,
          MAX(computed_at) AS computed_at
        FROM crm_account_health_score
        """,
        rs -> {
          if (!rs.next()) {
            return new HealthKpis(0, 0, 0, 0, 0, 0, 0, null);
          }
          return new HealthKpis(
              round1(rs.getDouble("avg_score")),
              round1(rs.getDouble("healthy_pct")),
              round1(rs.getDouble("moderate_pct")),
              rs.getLong("at_risk_count"),
              rs.getLong("churning_count"),
              sumMrrAtRiskPaise(),
              countOpenSavePlayAccounts(),
              ts(rs, "computed_at"));
        });
  }

  private AtRiskRow mapAtRisk(ResultSet rs, int i) throws SQLException {
    Date renewal = rs.getDate("renewal_date");
    return new AtRiskRow(
        (UUID) rs.getObject("account_id"),
        rs.getString("pharmacy_name"),
        rs.getString("plan"),
        rs.getLong("mrr_paise"),
        rs.getDouble("overall_score"),
        rs.getString("health_band"),
        renewal == null ? null : renewal.toLocalDate(),
        ts(rs, "last_save_play_at"),
        null);
  }

  private AccountHealthScore mapScore(ResultSet rs, int i) throws SQLException {
    return new AccountHealthScore(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("account_id"),
        rs.getDouble("overall_score"),
        rs.getDouble("product_usage_score"),
        rs.getDouble("billing_health_score"),
        rs.getDouble("support_satisfaction_score"),
        rs.getDouble("business_performance_score"),
        rs.getString("health_band"),
        textList(rs.getArray("risk_factors")),
        textList(rs.getArray("recommended_actions")),
        ts(rs, "computed_at"));
  }

  private static List<String> textList(Array arr) throws SQLException {
    if (arr == null) {
      return List.of();
    }
    Object raw = arr.getArray();
    if (raw instanceof String[] strings) {
      return Arrays.asList(strings);
    }
    return List.of();
  }

  private static Object[] toArray(List<String> values) {
    if (values.isEmpty()) {
      return new String[0];
    }
    return values.toArray(new String[0]);
  }

  private static BigDecimal bd(double v) {
    return BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP);
  }

  private static double round1(double v) {
    return BigDecimal.valueOf(v).setScale(1, java.math.RoundingMode.HALF_UP).doubleValue();
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}

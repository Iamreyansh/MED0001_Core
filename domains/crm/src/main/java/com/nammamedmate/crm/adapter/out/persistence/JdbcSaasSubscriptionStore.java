package com.nammamedmate.crm.adapter.out.persistence;

import com.nammamedmate.crm.application.port.out.SaasSubscriptionStore;
import com.nammamedmate.crm.domain.SaasSubscription;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcSaasSubscriptionStore implements SaasSubscriptionStore {

  private final JdbcTemplate jdbc;

  public JdbcSaasSubscriptionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<SaasSubscription> MAPPER = (rs, i) -> map(rs);

  private static SaasSubscription map(ResultSet rs) throws SQLException {
    return new SaasSubscription(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("account_id"),
        (UUID) rs.getObject("plan_id"),
        (UUID) rs.getObject("scheduled_plan_id"),
        rs.getString("status"),
        rs.getString("billing_cycle"),
        ts(rs, "renewal_date"),
        ts(rs, "trial_ends_at"),
        rs.getBoolean("auto_renew"),
        ts(rs, "cancelled_at"),
        ts(rs, "cancels_at"),
        ts(rs, "expires_at"),
        ts(rs, "past_due_at"),
        (UUID) rs.getObject("last_invoice_id"),
        (UUID) rs.getObject("override_plan_id"),
        ts(rs, "override_expires_at"),
        rs.getString("override_reason"),
        ts(rs, "created_at"),
        ts(rs, "updated_at"));
  }

  private static final String SELECT =
      """
      SELECT id, account_id, plan_id, scheduled_plan_id, status, billing_cycle, renewal_date,
             trial_ends_at, auto_renew, cancelled_at, cancels_at, expires_at, past_due_at,
             last_invoice_id, override_plan_id, override_expires_at, override_reason,
             created_at, updated_at
      FROM saas_subscription
      """;

  @Override
  public Optional<SaasSubscription> findByAccountId(UUID accountId) {
    List<SaasSubscription> rows =
        jdbc.query(SELECT + " WHERE account_id = ? AND deleted_at IS NULL", MAPPER, accountId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<SaasSubscription> findById(UUID id) {
    List<SaasSubscription> rows =
        jdbc.query(SELECT + " WHERE id = ? AND deleted_at IS NULL", MAPPER, id);
    return rows.stream().findFirst();
  }

  @Override
  public void insert(SaasSubscription sub) {
    jdbc.update(
        """
        INSERT INTO saas_subscription (
          id, account_id, plan_id, scheduled_plan_id, status, billing_cycle, renewal_date,
          trial_ends_at, auto_renew, cancelled_at, cancels_at, expires_at, past_due_at,
          last_invoice_id, override_plan_id, override_expires_at, override_reason,
          created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        sub.id(),
        sub.accountId(),
        sub.planId(),
        sub.scheduledPlanId(),
        sub.status(),
        sub.billingCycle(),
        ts(sub.renewalDate()),
        ts(sub.trialEndsAt()),
        sub.autoRenew(),
        ts(sub.cancelledAt()),
        ts(sub.cancelsAt()),
        ts(sub.expiresAt()),
        ts(sub.pastDueAt()),
        sub.lastInvoiceId(),
        sub.overridePlanId(),
        ts(sub.overrideExpiresAt()),
        sub.overrideReason(),
        ts(sub.createdAt()),
        ts(sub.updatedAt()));
  }

  @Override
  public void update(SaasSubscription sub) {
    jdbc.update(
        """
        UPDATE saas_subscription SET
          plan_id = ?, scheduled_plan_id = ?, status = ?, billing_cycle = ?, renewal_date = ?,
          trial_ends_at = ?, auto_renew = ?, cancelled_at = ?, cancels_at = ?, expires_at = ?,
          past_due_at = ?, last_invoice_id = ?, override_plan_id = ?, override_expires_at = ?,
          override_reason = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        sub.planId(),
        sub.scheduledPlanId(),
        sub.status(),
        sub.billingCycle(),
        ts(sub.renewalDate()),
        ts(sub.trialEndsAt()),
        sub.autoRenew(),
        ts(sub.cancelledAt()),
        ts(sub.cancelsAt()),
        ts(sub.expiresAt()),
        ts(sub.pastDueAt()),
        sub.lastInvoiceId(),
        sub.overridePlanId(),
        ts(sub.overrideExpiresAt()),
        sub.overrideReason(),
        ts(sub.updatedAt()),
        sub.id());
  }

  @Override
  public List<SaasSubscription> findDueForAutoRenew(Instant now, Instant windowEnd) {
    return jdbc.query(
        SELECT
            + """
              WHERE deleted_at IS NULL
                AND auto_renew = TRUE
                AND status = 'ACTIVE'
                AND cancels_at IS NULL
                AND renewal_date > ? AND renewal_date <= ?
              """,
        MAPPER,
        Timestamp.from(now),
        Timestamp.from(windowEnd));
  }

  @Override
  public List<SaasSubscription> findPastDueExpired(Instant graceCutoff) {
    return jdbc.query(
        SELECT
            + """
              WHERE deleted_at IS NULL
                AND status = 'PAST_DUE'
                AND past_due_at IS NOT NULL
                AND past_due_at <= ?
              """,
        MAPPER,
        Timestamp.from(graceCutoff));
  }

  @Override
  public List<SaasSubscription> findTrialsEnding(Instant now) {
    return jdbc.query(
        SELECT
            + """
              WHERE deleted_at IS NULL
                AND status = 'TRIAL'
                AND trial_ends_at IS NOT NULL
                AND trial_ends_at <= ?
              """,
        MAPPER,
        Timestamp.from(now));
  }

  @Override
  public List<SaasSubscription> findCancelsDue(Instant now) {
    return jdbc.query(
        SELECT
            + """
              WHERE deleted_at IS NULL
                AND cancels_at IS NOT NULL
                AND cancels_at <= ?
                AND status IN ('ACTIVE', 'TRIAL', 'PAST_DUE')
              """,
        MAPPER,
        Timestamp.from(now));
  }

  @Override
  public List<SaasSubscription> findOverridesExpired(Instant now) {
    return jdbc.query(
        SELECT
            + """
              WHERE deleted_at IS NULL
                AND override_plan_id IS NOT NULL
                AND override_expires_at IS NOT NULL
                AND override_expires_at <= ?
              """,
        MAPPER,
        Timestamp.from(now));
  }

  @Override
  public void updateAccountDenorm(
      UUID accountId, String planName, String status, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE crm_account SET current_plan_name = ?, status = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        planName,
        status,
        Timestamp.from(updatedAt),
        accountId);
  }

  @Override
  public Optional<UUID> findPharmacyId(UUID accountId) {
    List<UUID> rows =
        jdbc.query(
            "SELECT pharmacy_id FROM crm_account WHERE id = ? AND deleted_at IS NULL",
            (rs, i) -> (UUID) rs.getObject("pharmacy_id"),
            accountId);
    return rows.stream().findFirst();
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}

package com.nammamedmate.crm.adapter.out.persistence;

import com.nammamedmate.crm.application.port.out.SaasModuleUsageStore;
import com.nammamedmate.crm.domain.AccountModuleOverride;
import com.nammamedmate.crm.domain.ModuleMatrixRow;
import com.nammamedmate.crm.domain.ModuleUsageMonthly;
import com.nammamedmate.kernel.id.Ids;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcSaasModuleUsageStore implements SaasModuleUsageStore {

  private final JdbcTemplate jdbc;

  public JdbcSaasModuleUsageStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ModuleMatrixRow> findModuleById(String moduleId) {
    List<ModuleMatrixRow> rows =
        jdbc.query(
            """
            SELECT id, module_id, module_name, module_code, group_name, plan_names
            FROM saas_module_matrix WHERE module_id = ?
            """,
            this::mapModule,
            moduleId);
    return rows.stream().findFirst();
  }

  @Override
  public List<ModuleMatrixRow> listModuleMatrix() {
    return jdbc.query(
        """
        SELECT id, module_id, module_name, module_code, group_name, plan_names
        FROM saas_module_matrix
        ORDER BY group_name, module_id
        """,
        this::mapModule);
  }

  @Override
  public long countEligibleAccounts(String moduleId) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM crm_account a
            JOIN saas_module_matrix m ON m.module_id = ?
            WHERE a.deleted_at IS NULL
              AND a.status = 'ACTIVE'
              AND a.current_plan_name = ANY(m.plan_names)
            """,
            Long.class,
            moduleId);
    return n == null ? 0L : n;
  }

  @Override
  public long countAccountsUsing(String moduleId, LocalDate eventMonth) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(DISTINCT u.account_id)
            FROM saas_module_usage_monthly u
            JOIN crm_account a ON a.id = u.account_id AND a.deleted_at IS NULL
            WHERE u.module_id = ? AND u.event_month = ? AND u.event_count > 0
            """,
            Long.class,
            moduleId,
            java.sql.Date.valueOf(eventMonth));
    return n == null ? 0L : n;
  }

  @Override
  public List<EligibleAccountRow> listEligibleNotUsing(String moduleId, LocalDate eventMonth) {
    return jdbc.query(
        """
        SELECT a.id AS account_id, ph.name AS pharmacy_name,
               COALESCE(ov.enabled, TRUE) AS module_enabled,
               COALESCE(u.event_count, 0) AS event_count,
               u.last_active_at
        FROM crm_account a
        JOIN pharmacies ph ON ph.id = a.pharmacy_id AND ph.deleted_at IS NULL
        JOIN saas_module_matrix m ON m.module_id = ?
        LEFT JOIN crm_account_module_override ov
          ON ov.account_id = a.id AND ov.module_id = m.module_id
        LEFT JOIN saas_module_usage_monthly u
          ON u.account_id = a.id AND u.module_id = m.module_id AND u.event_month = ?
        WHERE a.deleted_at IS NULL
          AND a.status = 'ACTIVE'
          AND a.current_plan_name = ANY(m.plan_names)
          AND COALESCE(u.event_count, 0) = 0
        ORDER BY ph.name ASC
        """,
        (rs, i) ->
            new EligibleAccountRow(
                (UUID) rs.getObject("account_id"),
                rs.getString("pharmacy_name"),
                rs.getBoolean("module_enabled"),
                rs.getInt("event_count"),
                ts(rs, "last_active_at")),
        moduleId,
        java.sql.Date.valueOf(eventMonth));
  }

  @Override
  public List<AccountUsageRow> listPerAccountUsage(String moduleId, LocalDate eventMonth) {
    return jdbc.query(
        """
        SELECT a.id AS account_id, ph.name AS pharmacy_name,
               u.event_count, u.last_active_at
        FROM saas_module_usage_monthly u
        JOIN crm_account a ON a.id = u.account_id AND a.deleted_at IS NULL
        JOIN pharmacies ph ON ph.id = a.pharmacy_id AND ph.deleted_at IS NULL
        WHERE u.module_id = ? AND u.event_month = ? AND u.event_count > 0
        ORDER BY u.event_count DESC, ph.name ASC
        """,
        (rs, i) ->
            new AccountUsageRow(
                (UUID) rs.getObject("account_id"),
                rs.getString("pharmacy_name"),
                rs.getInt("event_count"),
                ts(rs, "last_active_at")),
        moduleId,
        java.sql.Date.valueOf(eventMonth));
  }

  @Override
  public Optional<AccountModuleOverride> findOverride(UUID accountId, String moduleId) {
    List<AccountModuleOverride> rows =
        jdbc.query(
            """
            SELECT id, account_id, module_id, enabled, reason, toggled_by, toggled_at
            FROM crm_account_module_override
            WHERE account_id = ? AND module_id = ?
            """,
            this::mapOverride,
            accountId,
            moduleId);
    return rows.stream().findFirst();
  }

  @Override
  public AccountModuleOverride upsertOverride(
      UUID accountId,
      String moduleId,
      boolean enabled,
      String reason,
      UUID toggledBy,
      Instant toggledAt) {
    UUID id = Ids.newId();
    jdbc.update(
        """
        INSERT INTO crm_account_module_override
          (id, account_id, module_id, enabled, reason, toggled_by, toggled_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (account_id, module_id) DO UPDATE SET
          enabled = EXCLUDED.enabled,
          reason = EXCLUDED.reason,
          toggled_by = EXCLUDED.toggled_by,
          toggled_at = EXCLUDED.toggled_at,
          updated_at = EXCLUDED.updated_at
        """,
        id,
        accountId,
        moduleId,
        enabled,
        reason,
        toggledBy,
        Timestamp.from(toggledAt),
        Timestamp.from(toggledAt),
        Timestamp.from(toggledAt));
    return findOverride(accountId, moduleId)
        .orElseGet(
            () ->
                new AccountModuleOverride(
                    id, accountId, moduleId, enabled, reason, toggledBy, toggledAt));
  }

  @Override
  public void incrementUsage(UUID accountId, String moduleId, LocalDate eventMonth, Instant at) {
    UUID id = Ids.newId();
    jdbc.update(
        """
        INSERT INTO saas_module_usage_monthly
          (id, account_id, module_id, event_month, event_count, last_active_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, 1, ?, ?, ?)
        ON CONFLICT (account_id, module_id, event_month) DO UPDATE SET
          event_count = saas_module_usage_monthly.event_count + 1,
          last_active_at = EXCLUDED.last_active_at,
          updated_at = EXCLUDED.updated_at
        """,
        id,
        accountId,
        moduleId,
        java.sql.Date.valueOf(eventMonth),
        Timestamp.from(at),
        Timestamp.from(at),
        Timestamp.from(at));
  }

  @Override
  public List<ModuleUsageMonthly> listAccountUsageMonth(UUID accountId, LocalDate eventMonth) {
    return jdbc.query(
        """
        SELECT id, account_id, module_id, event_month, event_count, last_active_at
        FROM saas_module_usage_monthly
        WHERE account_id = ? AND event_month = ?
        ORDER BY module_id
        """,
        this::mapUsage,
        accountId,
        java.sql.Date.valueOf(eventMonth));
  }

  @Override
  public Instant maxLastActive(UUID accountId) {
    return jdbc.query(
        """
            SELECT MAX(last_active_at) AS last_active_at
            FROM saas_module_usage_monthly WHERE account_id = ?
            """,
        rs -> rs.next() ? ts(rs, "last_active_at") : null,
        accountId);
  }

  @Override
  public int countModulesUsedSince(UUID accountId, Instant since) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(DISTINCT module_id)
            FROM saas_module_usage_monthly
            WHERE account_id = ? AND last_active_at >= ? AND event_count > 0
            """,
            Long.class,
            accountId,
            Timestamp.from(since));
    return n == null ? 0 : n.intValue();
  }

  @Override
  public long countActiveStaff(UUID pharmacyId) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(DISTINCT s.id)
            FROM pharmacy_staff s
            JOIN pharmacy_staff_assignment a ON a.staff_id = s.id
            WHERE a.pharmacy_id = ?
              AND a.is_active = TRUE
              AND a.removed_at IS NULL
              AND s.deleted_at IS NULL
              AND s.status = 'ACTIVE'
            """,
            Long.class,
            pharmacyId);
    return n == null ? 0L : n;
  }

  @Override
  public List<String> listActiveStaffNames(UUID pharmacyId) {
    return jdbc.query(
        """
        SELECT s.name
        FROM pharmacy_staff s
        JOIN pharmacy_staff_assignment a ON a.staff_id = s.id
        WHERE a.pharmacy_id = ?
          AND a.is_active = TRUE
          AND a.removed_at IS NULL
          AND s.deleted_at IS NULL
          AND s.status = 'ACTIVE'
        ORDER BY s.name
        """,
        (rs, i) -> rs.getString("name"),
        pharmacyId);
  }

  @Override
  public long countInvoicesThisMonth(
      UUID pharmacyId, Instant monthStart, Instant monthEndExclusive) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM invoice
            WHERE pharmacy_id = ?
              AND created_at >= ? AND created_at < ?
              AND status = 'ACTIVE'
            """,
            Long.class,
            pharmacyId,
            Timestamp.from(monthStart),
            Timestamp.from(monthEndExclusive));
    return n == null ? 0L : n;
  }

  @Override
  public String pharmacyName(UUID pharmacyId) {
    List<String> names =
        jdbc.query(
            "SELECT name FROM pharmacies WHERE id = ? AND deleted_at IS NULL",
            (rs, i) -> rs.getString("name"),
            pharmacyId);
    return names.isEmpty() ? null : names.getFirst();
  }

  @Override
  public List<UUID> listNudgeTargetAccountIds(String moduleId, Instant activeSince) {
    return jdbc.query(
        """
        SELECT a.id
        FROM crm_account a
        JOIN saas_module_matrix m ON m.module_id = ?
        WHERE a.deleted_at IS NULL
          AND a.status = 'ACTIVE'
          AND a.current_plan_name = ANY(m.plan_names)
          AND NOT EXISTS (
            SELECT 1 FROM saas_module_usage_monthly u
            WHERE u.account_id = a.id
              AND u.module_id = m.module_id
              AND u.last_active_at >= ?
              AND u.event_count > 0
          )
        """,
        (rs, i) -> (UUID) rs.getObject("id"),
        moduleId,
        Timestamp.from(activeSince));
  }

  private ModuleMatrixRow mapModule(ResultSet rs, int i) throws SQLException {
    Array arr = rs.getArray("plan_names");
    String[] names = arr == null ? new String[0] : (String[]) arr.getArray();
    return new ModuleMatrixRow(
        (UUID) rs.getObject("id"),
        rs.getString("module_id"),
        rs.getString("module_name"),
        rs.getString("module_code"),
        rs.getString("group_name"),
        Arrays.asList(names));
  }

  private AccountModuleOverride mapOverride(ResultSet rs, int i) throws SQLException {
    return new AccountModuleOverride(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("account_id"),
        rs.getString("module_id"),
        rs.getBoolean("enabled"),
        rs.getString("reason"),
        (UUID) rs.getObject("toggled_by"),
        ts(rs, "toggled_at"));
  }

  private ModuleUsageMonthly mapUsage(ResultSet rs, int i) throws SQLException {
    java.sql.Date month = rs.getDate("event_month");
    return new ModuleUsageMonthly(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("account_id"),
        rs.getString("module_id"),
        month == null ? null : month.toLocalDate(),
        rs.getInt("event_count"),
        ts(rs, "last_active_at"));
  }

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}

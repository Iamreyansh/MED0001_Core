package com.nammamedmate.crm.adapter.out.persistence;

import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.domain.AccountAddon;
import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.ModuleMatrixRow;
import com.nammamedmate.crm.domain.PlanSubscriber;
import com.nammamedmate.crm.domain.SaasAddon;
import com.nammamedmate.crm.domain.SaasPlan;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcSaasPlanStore implements SaasPlanStore {

  private final JdbcTemplate jdbc;

  public JdbcSaasPlanStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<SaasPlan> PLAN_MAPPER =
      (rs, i) ->
          new SaasPlan(
              (UUID) rs.getObject("id"),
              rs.getString("name"),
              rs.getLong("price_monthly_paise"),
              (Integer) rs.getObject("seat_limit"),
              (Integer) rs.getObject("invoice_cap_monthly"),
              rs.getBoolean("is_active"),
              rs.getBoolean("is_custom_pricing"),
              ts(rs, "updated_at"));

  private static final RowMapper<SaasAddon> ADDON_MAPPER =
      (rs, i) ->
          new SaasAddon(
              (UUID) rs.getObject("id"),
              rs.getString("name"),
              rs.getLong("price_monthly_paise"),
              rs.getString("description"),
              rs.getBoolean("is_active"));

  private static final RowMapper<CrmAccount> ACCOUNT_MAPPER =
      (rs, i) ->
          new CrmAccount(
              (UUID) rs.getObject("id"),
              (UUID) rs.getObject("pharmacy_id"),
              rs.getString("current_plan_name"),
              rs.getString("status"),
              ts(rs, "created_at"));

  @Override
  public List<SaasPlan> listActivePlans() {
    return jdbc.query(
        """
        SELECT id, name, price_monthly_paise, seat_limit, invoice_cap_monthly,
               is_active, is_custom_pricing, updated_at
        FROM saas_plan
        WHERE deleted_at IS NULL AND is_active = TRUE
        ORDER BY CASE name
          WHEN 'FREE' THEN 1 WHEN 'STARTER' THEN 2
          WHEN 'RETAIL_PRO' THEN 3 WHEN 'ENTERPRISE' THEN 4 ELSE 9 END
        """,
        PLAN_MAPPER);
  }

  @Override
  public Optional<SaasPlan> findPlanById(UUID id) {
    List<SaasPlan> rows =
        jdbc.query(
            """
            SELECT id, name, price_monthly_paise, seat_limit, invoice_cap_monthly,
                   is_active, is_custom_pricing, updated_at
            FROM saas_plan WHERE id = ? AND deleted_at IS NULL
            """,
            PLAN_MAPPER,
            id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<SaasPlan> findPlanByName(String name) {
    List<SaasPlan> rows =
        jdbc.query(
            """
            SELECT id, name, price_monthly_paise, seat_limit, invoice_cap_monthly,
                   is_active, is_custom_pricing, updated_at
            FROM saas_plan WHERE name = ? AND deleted_at IS NULL
            """,
            PLAN_MAPPER,
            name);
    return rows.stream().findFirst();
  }

  @Override
  public SaasPlan updatePlan(
      UUID id,
      Long priceMonthlyPaise,
      Integer seatLimit,
      Integer invoiceCapMonthly,
      Instant updatedAt) {
    jdbc.update(
        """
        UPDATE saas_plan SET
          price_monthly_paise = COALESCE(?, price_monthly_paise),
          seat_limit = COALESCE(?, seat_limit),
          invoice_cap_monthly = COALESCE(?, invoice_cap_monthly),
          updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        priceMonthlyPaise,
        seatLimit,
        invoiceCapMonthly,
        Timestamp.from(updatedAt),
        id);
    return findPlanById(id).orElseThrow();
  }

  @Override
  public long countActiveSubscribers(String planName) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM crm_account
            WHERE current_plan_name = ? AND status = 'ACTIVE' AND deleted_at IS NULL
            """,
            Long.class,
            planName);
    return n == null ? 0L : n;
  }

  @Override
  public List<PlanSubscriber> listSubscribers(String planName, int offset, int limit) {
    return jdbc.query(
        """
        SELECT a.id AS account_id,
               COALESCE(p.business_name, p.name, 'Pharmacy') AS pharmacy_name,
               a.created_at AS since
        FROM crm_account a
        LEFT JOIN pharmacies p ON p.id = a.pharmacy_id
        WHERE a.current_plan_name = ? AND a.status = 'ACTIVE' AND a.deleted_at IS NULL
        ORDER BY a.created_at ASC
        OFFSET ? LIMIT ?
        """,
        (rs, i) ->
            new PlanSubscriber(
                (UUID) rs.getObject("account_id"), rs.getString("pharmacy_name"), ts(rs, "since")),
        planName,
        offset,
        limit);
  }

  @Override
  public long countModulesForPlan(String planName) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM saas_module_matrix
            WHERE ? = ANY(plan_names)
            """,
            Long.class,
            planName);
    return n == null ? 0L : n;
  }

  @Override
  public List<String> moduleCodesForPlan(String planName) {
    return jdbc.query(
        """
        SELECT module_code FROM saas_module_matrix
        WHERE ? = ANY(plan_names)
        ORDER BY group_name, module_code
        """,
        (rs, i) -> rs.getString("module_code"),
        planName);
  }

  @Override
  public List<SaasAddon> listActiveAddons() {
    return jdbc.query(
        """
        SELECT id, name, price_monthly_paise, description, is_active
        FROM saas_addon
        WHERE deleted_at IS NULL AND is_active = TRUE
        ORDER BY name
        """,
        ADDON_MAPPER);
  }

  @Override
  public Optional<SaasAddon> findAddonById(UUID id) {
    List<SaasAddon> rows =
        jdbc.query(
            """
            SELECT id, name, price_monthly_paise, description, is_active
            FROM saas_addon WHERE id = ? AND deleted_at IS NULL
            """,
            ADDON_MAPPER,
            id);
    return rows.stream().findFirst();
  }

  @Override
  public long countActiveAccountsWithAddon(UUID addonId) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM crm_account_addon aa
            JOIN crm_account a ON a.id = aa.account_id
            WHERE aa.addon_id = ? AND aa.detached_at IS NULL
              AND a.status = 'ACTIVE' AND a.deleted_at IS NULL
            """,
            Long.class,
            addonId);
    return n == null ? 0L : n;
  }

  @Override
  public long countTotalActiveAccounts() {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM crm_account
            WHERE status = 'ACTIVE' AND deleted_at IS NULL
            """,
            Long.class);
    return n == null ? 0L : n;
  }

  @Override
  public Optional<CrmAccount> findAccountById(UUID accountId) {
    List<CrmAccount> rows =
        jdbc.query(
            """
            SELECT id, pharmacy_id, current_plan_name, status, created_at
            FROM crm_account WHERE id = ? AND deleted_at IS NULL
            """,
            ACCOUNT_MAPPER,
            accountId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<CrmAccount> findAccountByPharmacyId(UUID pharmacyId) {
    List<CrmAccount> rows =
        jdbc.query(
            """
            SELECT id, pharmacy_id, current_plan_name, status, created_at
            FROM crm_account WHERE pharmacy_id = ? AND deleted_at IS NULL
            """,
            ACCOUNT_MAPPER,
            pharmacyId);
    return rows.stream().findFirst();
  }

  @Override
  public List<CrmAccount> listActiveAccounts() {
    return jdbc.query(
        """
        SELECT id, pharmacy_id, current_plan_name, status, created_at
        FROM crm_account
        WHERE deleted_at IS NULL AND status = 'ACTIVE'
        ORDER BY created_at
        """,
        ACCOUNT_MAPPER);
  }

  @Override
  public CrmAccount createAccount(UUID pharmacyId, String planName, String status, Instant now) {
    UUID id = com.nammamedmate.kernel.id.Ids.newId();
    jdbc.update(
        """
        INSERT INTO crm_account (id, pharmacy_id, current_plan_name, status, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (pharmacy_id) DO NOTHING
        """,
        id,
        pharmacyId,
        planName,
        status,
        Timestamp.from(now),
        Timestamp.from(now));
    return findAccountByPharmacyId(pharmacyId)
        .orElseGet(() -> new CrmAccount(id, pharmacyId, planName, status, now));
  }

  @Override
  public Optional<AccountAddon> findActiveAccountAddon(UUID accountId, UUID addonId) {
    List<AccountAddon> rows =
        jdbc.query(
            """
            SELECT account_id, addon_id, effective_from, detached_at
            FROM crm_account_addon
            WHERE account_id = ? AND addon_id = ? AND detached_at IS NULL
            """,
            (rs, i) ->
                new AccountAddon(
                    (UUID) rs.getObject("account_id"),
                    (UUID) rs.getObject("addon_id"),
                    ts(rs, "effective_from"),
                    rs.getTimestamp("detached_at") == null
                        ? null
                        : rs.getTimestamp("detached_at").toInstant()),
            accountId,
            addonId);
    return rows.stream().findFirst();
  }

  @Override
  public void attachAddon(UUID accountId, UUID addonId, Instant effectiveFrom) {
    jdbc.update(
        """
        INSERT INTO crm_account_addon (account_id, addon_id, effective_from, detached_at)
        VALUES (?, ?, ?, NULL)
        ON CONFLICT (account_id, addon_id) DO UPDATE
          SET effective_from = EXCLUDED.effective_from, detached_at = NULL
        WHERE crm_account_addon.detached_at IS NOT NULL
        """,
        accountId,
        addonId,
        Timestamp.from(effectiveFrom));
  }

  @Override
  public void detachAddon(UUID accountId, UUID addonId, Instant detachedAt) {
    jdbc.update(
        """
        UPDATE crm_account_addon SET detached_at = ?
        WHERE account_id = ? AND addon_id = ? AND detached_at IS NULL
        """,
        Timestamp.from(detachedAt),
        accountId,
        addonId);
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
  public boolean planIncludesModule(String planName, String moduleId) {
    Boolean ok =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM saas_module_matrix
              WHERE module_id = ? AND ? = ANY(plan_names)
            )
            """,
            Boolean.class,
            moduleId,
            planName);
    return Boolean.TRUE.equals(ok);
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

  private static Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}

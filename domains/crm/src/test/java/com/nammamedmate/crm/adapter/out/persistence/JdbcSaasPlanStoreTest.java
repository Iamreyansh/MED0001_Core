package com.nammamedmate.crm.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.ModuleMatrixRow;
import com.nammamedmate.crm.domain.SaasPlan;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcSaasPlanStoreTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;
  @Mock Array sqlArray;

  @Test
  @SuppressWarnings("unchecked")
  void coversQueriesAndMutations() throws Exception {
    JdbcSaasPlanStore store = new JdbcSaasPlanStore(jdbc);
    UUID planId = UUID.fromString("a1000000-0000-4000-8000-000000000002");
    UUID accountId = UUID.randomUUID();
    UUID pharmacyId = UUID.randomUUID();
    UUID addonId = UUID.fromString("a2000000-0000-4000-8000-000000000001");
    Instant now = Instant.parse("2026-07-15T10:00:00Z");

    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(inv -> mapBySql(inv.getArgument(0), inv.getArgument(1), planId, addonId, now));
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv ->
                mapBySqlArgs(
                    inv.getArgument(0),
                    inv.getArgument(1),
                    planId,
                    accountId,
                    pharmacyId,
                    addonId,
                    now));
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv ->
                mapBySqlArgs(
                    inv.getArgument(0),
                    inv.getArgument(1),
                    planId,
                    accountId,
                    pharmacyId,
                    addonId,
                    now));
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv ->
                mapBySqlArgs(
                    inv.getArgument(0),
                    inv.getArgument(1),
                    planId,
                    accountId,
                    pharmacyId,
                    addonId,
                    now));

    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(5L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(5L);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(10L);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any())).thenReturn(true);

    assertThat(store.listActivePlans()).hasSize(1);
    assertThat(store.findPlanById(planId)).isPresent();
    assertThat(store.findPlanByName("STARTER")).isPresent();
    assertThat(store.countActiveSubscribers("STARTER")).isEqualTo(5L);
    assertThat(store.countModulesForPlan("STARTER")).isEqualTo(5L);
    assertThat(store.moduleCodesForPlan("STARTER")).containsExactly("INVENTORY");
    assertThat(store.listSubscribers("STARTER", 0, 20)).hasSize(1);
    assertThat(store.listActiveAddons()).hasSize(1);
    assertThat(store.findAddonById(addonId)).isPresent();
    assertThat(store.countActiveAccountsWithAddon(addonId)).isEqualTo(5L);
    assertThat(store.countTotalActiveAccounts()).isEqualTo(10L);
    assertThat(store.findAccountById(accountId)).isPresent();
    assertThat(store.findAccountByPharmacyId(pharmacyId)).isPresent();
    assertThat(store.listActiveAccounts()).hasSize(1);
    assertThat(store.createAccount(pharmacyId, "FREE", "ACTIVE", now).pharmacyId())
        .isEqualTo(pharmacyId);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacyId))).thenReturn(List.of());
    CrmAccount fallback = store.createAccount(pharmacyId, "FREE", "ACTIVE", now);
    assertThat(fallback.pharmacyId()).isEqualTo(pharmacyId);
    assertThat(store.findActiveAccountAddon(accountId, addonId)).isPresent();
    List<ModuleMatrixRow> matrix = store.listModuleMatrix();
    assertThat(matrix.getFirst().planNames()).contains("STARTER");
    assertThat(store.planIncludesModule("STARTER", "mod_inventory")).isTrue();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              stubPlan(planId, 79900L, 3, 600, now);
              return List.of(mapper.mapRow(rs, 0));
            });
    SaasPlan updated = store.updatePlan(planId, 79900L, 3, 600, now);
    assertThat(updated.priceMonthlyPaise()).isEqualTo(79900L);
    verify(jdbc).update(anyString(), any(), any(), any(), any(), any());

    store.attachAddon(accountId, addonId, now);
    store.detachAddon(accountId, addonId, now);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any())).thenReturn(false);
    assertThat(store.countActiveSubscribers("X")).isZero();
    assertThat(store.countModulesForPlan("X")).isZero();
    assertThat(store.countActiveAccountsWithAddon(addonId)).isZero();
    assertThat(store.countTotalActiveAccounts()).isZero();
    assertThat(store.planIncludesModule("FREE", "mod_x")).isFalse();

    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getString("module_id")).thenReturn("mod_x");
              when(rs.getString("module_name")).thenReturn("X");
              when(rs.getString("module_code")).thenReturn("X");
              when(rs.getString("group_name")).thenReturn("CORE");
              when(rs.getArray("plan_names")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.listModuleMatrix().getFirst().planNames()).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              when(rs.getObject("account_id")).thenReturn(accountId);
              when(rs.getObject("addon_id")).thenReturn(addonId);
              when(rs.getTimestamp("effective_from")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("detached_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findActiveAccountAddon(accountId, addonId).get().detachedAt()).isEqualTo(now);
  }

  @SuppressWarnings("unchecked")
  private List<Object> mapBySql(
      String sql, RowMapper<Object> mapper, UUID planId, UUID addonId, Instant now)
      throws Exception {
    if (sql.contains("saas_module_matrix") && sql.contains("plan_names")) {
      when(rs.getObject("id")).thenReturn(UUID.randomUUID());
      when(rs.getString("module_id")).thenReturn("mod_inventory");
      when(rs.getString("module_name")).thenReturn("Inventory");
      when(rs.getString("module_code")).thenReturn("INVENTORY");
      when(rs.getString("group_name")).thenReturn("CORE");
      when(rs.getArray("plan_names")).thenReturn(sqlArray);
      when(sqlArray.getArray())
          .thenReturn(new String[] {"FREE", "STARTER", "RETAIL_PRO", "ENTERPRISE"});
      return List.of(mapper.mapRow(rs, 0));
    }
    if (sql.contains("FROM saas_addon")) {
      when(rs.getObject("id")).thenReturn(addonId);
      when(rs.getString("name")).thenReturn("E_INVOICE");
      when(rs.getLong("price_monthly_paise")).thenReturn(19900L);
      when(rs.getString("description")).thenReturn("e");
      when(rs.getBoolean("is_active")).thenReturn(true);
      return List.of(mapper.mapRow(rs, 0));
    }
    stubPlan(planId, 69900L, 2, 500, now);
    return List.of(mapper.mapRow(rs, 0));
  }

  @SuppressWarnings("unchecked")
  private List<Object> mapBySqlArgs(
      String sql,
      RowMapper<Object> mapper,
      UUID planId,
      UUID accountId,
      UUID pharmacyId,
      UUID addonId,
      Instant now)
      throws Exception {
    if (sql.contains("crm_account_addon")) {
      when(rs.getObject("account_id")).thenReturn(accountId);
      when(rs.getObject("addon_id")).thenReturn(addonId);
      when(rs.getTimestamp("effective_from")).thenReturn(Timestamp.from(now));
      when(rs.getTimestamp("detached_at")).thenReturn(null);
      return List.of(mapper.mapRow(rs, 0));
    }
    if (sql.contains("FROM crm_account")) {
      when(rs.getObject("id")).thenReturn(accountId);
      when(rs.getObject("pharmacy_id")).thenReturn(pharmacyId);
      when(rs.getString("current_plan_name")).thenReturn("STARTER");
      when(rs.getString("status")).thenReturn("ACTIVE");
      when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
      return List.of(mapper.mapRow(rs, 0));
    }
    if (sql.contains("module_code")) {
      when(rs.getString("module_code")).thenReturn("INVENTORY");
      return List.of(mapper.mapRow(rs, 0));
    }
    if (sql.contains("pharmacy_name")) {
      when(rs.getObject("account_id")).thenReturn(accountId);
      when(rs.getString("pharmacy_name")).thenReturn("Apollo");
      when(rs.getTimestamp("since")).thenReturn(Timestamp.from(now));
      return List.of(mapper.mapRow(rs, 0));
    }
    if (sql.contains("FROM saas_addon")) {
      when(rs.getObject("id")).thenReturn(addonId);
      when(rs.getString("name")).thenReturn("E_INVOICE");
      when(rs.getLong("price_monthly_paise")).thenReturn(19900L);
      when(rs.getString("description")).thenReturn("e");
      when(rs.getBoolean("is_active")).thenReturn(true);
      return List.of(mapper.mapRow(rs, 0));
    }
    stubPlan(planId, 69900L, 2, 500, now);
    return List.of(mapper.mapRow(rs, 0));
  }

  private void stubPlan(UUID planId, long paise, Integer seats, Integer cap, Instant now)
      throws Exception {
    when(rs.getObject("id")).thenReturn(planId);
    when(rs.getString("name")).thenReturn("STARTER");
    when(rs.getLong("price_monthly_paise")).thenReturn(paise);
    when(rs.getObject("seat_limit")).thenReturn(seats);
    when(rs.getObject("invoice_cap_monthly")).thenReturn(cap);
    when(rs.getBoolean("is_active")).thenReturn(true);
    when(rs.getBoolean("is_custom_pricing")).thenReturn(false);
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
  }
}

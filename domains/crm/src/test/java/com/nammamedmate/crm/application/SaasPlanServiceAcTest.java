package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.CrmAuditPort;
import com.nammamedmate.crm.application.port.out.SaasModuleUsageStore;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.domain.AccountAddon;
import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.ModuleMatrixRow;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.crm.domain.PlanSubscriber;
import com.nammamedmate.crm.domain.SaasAddon;
import com.nammamedmate.crm.domain.SaasPlan;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SaasPlanServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-15T10:00:00Z");

  @Mock SaasPlanStore store;
  @Mock SaasModuleUsageStore moduleUsage;
  @Mock SubscriptionService subscriptions;
  private RecordingAudit audit;
  private SaasPlanService service;
  private MedmatePrincipal superAdmin;
  private MedmatePrincipal finance;
  private MedmatePrincipal ops;
  private MedmatePrincipal owner;
  private UUID pharmacyId;
  private UUID starterId;
  private UUID freeId;
  private UUID accountId;
  private UUID addonId;

  @BeforeEach
  void setUp() {
    audit = new RecordingAudit();
    service =
        new SaasPlanService(
            store, moduleUsage, audit, Clock.fixed(NOW, ZoneOffset.UTC), subscriptions);
    pharmacyId = Ids.newId();
    starterId = UUID.fromString("a1000000-0000-4000-8000-000000000002");
    freeId = UUID.fromString("a1000000-0000-4000-8000-000000000001");
    accountId = Ids.newId();
    addonId = UUID.fromString("a2000000-0000-4000-8000-000000000001");
    superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    finance =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j2");
    ops = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j3");
    owner =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j4");
  }

  @Test
  @DisplayName("AC-001 admin lists plans with subscriber_count and mrr_rs")
  void ac001_listPlans() {
    when(store.listActivePlans())
        .thenReturn(
            List.of(
                plan(starterId, PlanNames.STARTER, 69900, 2, 500),
                plan(
                    UUID.fromString("a1000000-0000-4000-8000-000000000003"),
                    PlanNames.RETAIL_PRO,
                    149900,
                    5,
                    null)));
    when(store.countActiveSubscribers(PlanNames.STARTER)).thenReturn(420L);
    when(store.countActiveSubscribers(PlanNames.RETAIL_PRO)).thenReturn(180L);
    when(store.countModulesForPlan(any())).thenReturn(8L);

    Map<String, Object> data = service.listPlansAdmin(finance);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> plans = (List<Map<String, Object>>) data.get("plans");
    assertThat(plans).hasSize(2);
    assertThat(plans.get(0))
        .containsEntry("subscriber_count", 420L)
        .containsEntry("mrr_rs", new BigDecimal("293580.00"));
    assertThat(plans.get(0).get("price_annual_rs")).isEqualTo(new BigDecimal("6990.00"));
  }

  @Test
  @DisplayName("AC-002 annual = monthly × 10 on plan detail")
  void ac002_planDetailAnnual() {
    when(store.findPlanById(starterId))
        .thenReturn(Optional.of(plan(starterId, PlanNames.STARTER, 69900, 2, 500)));
    when(store.countActiveSubscribers(PlanNames.STARTER)).thenReturn(1L);
    when(store.listSubscribers(eq(PlanNames.STARTER), eq(0), eq(20)))
        .thenReturn(List.of(new PlanSubscriber(accountId, "Apollo", NOW)));
    when(store.moduleCodesForPlan(PlanNames.STARTER)).thenReturn(List.of("INVENTORY", "BILLING"));

    Map<String, Object> data = service.getPlanAdmin(ops, starterId, null, null);
    @SuppressWarnings("unchecked")
    Map<String, Object> pricing = (Map<String, Object>) data.get("pricing");
    assertThat(pricing.get("annual_rs")).isEqualTo(new BigDecimal("6990.00"));
    assertThat(pricing.get("annual_savings_pct")).isEqualTo(new BigDecimal("16.7"));
    assertThat(data.get("upgrade_path")).isEqualTo(PlanNames.RETAIL_PRO);
  }

  @Test
  @DisplayName("AC-003 update STARTER price audits old 699 and new 799")
  void ac003_updateAudited() {
    SaasPlan before = plan(starterId, PlanNames.STARTER, 69900, 2, 500);
    SaasPlan after = plan(starterId, PlanNames.STARTER, 79900, 3, 600);
    when(store.findPlanById(starterId)).thenReturn(Optional.of(before));
    when(store.updatePlan(eq(starterId), eq(79900L), eq(3), eq(600), eq(NOW))).thenReturn(after);

    Map<String, Object> data =
        service.updatePlan(superAdmin, starterId, new BigDecimal("799"), 3, 600);
    assertThat(data)
        .containsEntry("plan_id", starterId)
        .containsEntry("updated_by", superAdmin.subject());
    assertThat(audit.actions).containsExactly("saas_plan.updated");
    assertThat(audit.befores.get(0)).containsEntry("price_monthly_rs", new BigDecimal("699.00"));
    assertThat(audit.afters.get(0)).containsEntry("price_monthly_rs", new BigDecimal("799.00"));
  }

  @Test
  @DisplayName("AC-004 / AC-010 FREE price change refused; successful change is audited")
  void ac004_cannotModifyFreePrice() {
    when(store.findPlanById(freeId))
        .thenReturn(Optional.of(plan(freeId, PlanNames.FREE, 0, 1, 100)));
    assertThatThrownBy(() -> service.updatePlan(superAdmin, freeId, BigDecimal.ONE, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CANNOT_MODIFY_FREE_PLAN_PRICE");
    verify(store, never()).updatePlan(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("AC-005 attach already-attached add-on → ADDON_ALREADY_ATTACHED")
  void ac005_alreadyAttached() {
    when(store.findAccountById(accountId))
        .thenReturn(
            Optional.of(new CrmAccount(accountId, pharmacyId, PlanNames.STARTER, "ACTIVE", NOW)));
    when(store.findAddonById(addonId))
        .thenReturn(Optional.of(new SaasAddon(addonId, "E_INVOICE", 19900, "e", true)));
    when(store.findActiveAccountAddon(accountId, addonId))
        .thenReturn(Optional.of(new AccountAddon(accountId, addonId, NOW, null)));

    assertThatThrownBy(() -> service.attachAddon(ops, accountId, addonId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ADDON_ALREADY_ATTACHED");
  }

  @Test
  @DisplayName("AC-006 detach mid-cycle returns prorated_credit_rs > 0")
  void ac006_proratedDetach() {
    when(store.findAccountById(accountId))
        .thenReturn(
            Optional.of(new CrmAccount(accountId, pharmacyId, PlanNames.STARTER, "ACTIVE", NOW)));
    when(store.findAddonById(addonId))
        .thenReturn(Optional.of(new SaasAddon(addonId, "E_INVOICE", 19900, "e", true)));
    when(store.findActiveAccountAddon(accountId, addonId))
        .thenReturn(
            Optional.of(new AccountAddon(accountId, addonId, NOW.minusSeconds(86400), null)));

    Map<String, Object> data = service.detachAddon(ops, accountId, addonId);
    assertThat(((BigDecimal) data.get("prorated_credit_rs")).compareTo(BigDecimal.ZERO))
        .isGreaterThan(0);
    verify(store).detachAddon(accountId, addonId, NOW);
  }

  @Test
  @DisplayName("AC-007 module matrix shows plan availability")
  void ac007_moduleMatrix() {
    when(store.listModuleMatrix())
        .thenReturn(
            List.of(
                new ModuleMatrixRow(
                    Ids.newId(),
                    "mod_inventory",
                    "Inventory Management",
                    "INVENTORY",
                    "CORE",
                    List.of("FREE", "STARTER", "RETAIL_PRO", "ENTERPRISE")),
                new ModuleMatrixRow(
                    Ids.newId(),
                    "mod_analytics_adv",
                    "Advanced Analytics",
                    "ADVANCED_ANALYTICS",
                    "ANALYTICS",
                    List.of("RETAIL_PRO", "ENTERPRISE"))));

    Map<String, Object> data = service.moduleMatrix(finance);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> modules = (List<Map<String, Object>>) data.get("modules");
    assertThat(modules).hasSize(2);
    assertThat(modules.get(1).get("available_on")).isEqualTo(List.of("RETAIL_PRO", "ENTERPRISE"));
  }

  @Test
  @DisplayName("AC-008 pharmacy plans show is_current and upgrade_cta")
  void ac008_pharmacyPlans() {
    when(subscriptions.effectivePlanName(pharmacyId)).thenReturn(PlanNames.STARTER);
    UUID retailId = UUID.fromString("a1000000-0000-4000-8000-000000000003");
    when(store.listActivePlans())
        .thenReturn(
            List.of(
                plan(starterId, PlanNames.STARTER, 69900, 2, 500),
                plan(retailId, PlanNames.RETAIL_PRO, 149900, 5, null)));
    when(store.moduleCodesForPlan(any())).thenReturn(List.of("INVENTORY"));

    Map<String, Object> data = service.listPlansForPharmacy(owner);
    assertThat(data.get("current_plan")).isEqualTo(PlanNames.STARTER);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> plans = (List<Map<String, Object>>) data.get("plans");
    assertThat(plans.get(0)).containsEntry("is_current", true);
    assertThat(plans.get(1))
        .containsEntry("is_current", false)
        .containsEntry("upgrade_cta", "Upgrade Now");
  }

  @Test
  @DisplayName("AC-009 attach_rate_pct formula on addons list")
  void ac009_attachRate() {
    when(store.countTotalActiveAccounts()).thenReturn(100L);
    when(store.listActiveAddons())
        .thenReturn(List.of(new SaasAddon(addonId, "E_INVOICE", 19900, "e", true)));
    when(store.countActiveAccountsWithAddon(addonId)).thenReturn(34L);

    Map<String, Object> data = service.listAddons(finance);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> addons = (List<Map<String, Object>>) data.get("addons");
    assertThat(addons.get(0).get("attach_rate_pct")).isEqualTo(new BigDecimal("34.0"));
  }

  @Test
  void attachHappyPathAndErrors() {
    when(store.findAccountById(accountId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.attachAddon(ops, accountId, addonId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ACCOUNT_NOT_FOUND");

    when(store.findAccountById(accountId))
        .thenReturn(
            Optional.of(new CrmAccount(accountId, pharmacyId, PlanNames.FREE, "ACTIVE", NOW)));
    when(store.findAddonById(addonId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.attachAddon(ops, accountId, addonId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ADDON_NOT_FOUND");

    when(store.findAddonById(addonId))
        .thenReturn(Optional.of(new SaasAddon(addonId, "E_INVOICE", 19900, "e", true)));
    when(store.findActiveAccountAddon(accountId, addonId)).thenReturn(Optional.empty());
    Map<String, Object> attached = service.attachAddon(ops, accountId, addonId);
    assertThat(attached.get("next_billing_amount_rs")).isEqualTo(new BigDecimal("199.00"));
    verify(store).attachAddon(accountId, addonId, NOW);
  }

  @Test
  void updateForbiddenAndNotFound() {
    assertThatThrownBy(
            () -> service.updatePlan(finance, starterId, new BigDecimal("799"), null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    when(store.findPlanById(starterId)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> service.updatePlan(superAdmin, starterId, new BigDecimal("799"), null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");
  }

  @Test
  void pharmacyForbiddenAndDefaultFree() {
    assertThatThrownBy(() -> service.listPlansForPharmacy(finance))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    when(subscriptions.effectivePlanName(pharmacyId)).thenReturn(PlanNames.FREE);
    when(store.listActivePlans()).thenReturn(List.of(plan(freeId, PlanNames.FREE, 0, 1, 100)));
    when(store.moduleCodesForPlan(PlanNames.FREE)).thenReturn(List.of("INVENTORY"));
    Map<String, Object> data = service.listPlansForPharmacy(owner);
    assertThat(data.get("current_plan")).isEqualTo(PlanNames.FREE);
  }

  @Test
  void planLookupPort() {
    assertThat(service.planNameForPharmacy(null)).isEmpty();
    when(subscriptions.effectivePlanName(pharmacyId)).thenReturn(PlanNames.RETAIL_PRO);
    assertThat(service.planNameForPharmacy(pharmacyId)).contains(PlanNames.RETAIL_PRO);
    when(store.planIncludesModule(PlanNames.RETAIL_PRO, "mod_offers")).thenReturn(true);
    assertThat(service.planIncludesModule(PlanNames.RETAIL_PRO, "mod_offers")).isTrue();
    assertThat(service.planIncludesModule(null, "mod_offers")).isFalse();
  }

  @Test
  void requireAdminBranches() {
    MedmatePrincipal support =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "s");
    assertThatThrownBy(() -> service.listPlansAdmin(support))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.listPlansAdmin(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.attachAddon(finance, accountId, addonId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.attachAddon(null, accountId, addonId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    when(store.findPlanById(starterId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getPlanAdmin(ops, starterId, 1, 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");
  }

  @Test
  void detachNotAttached() {
    when(store.findAccountById(accountId))
        .thenReturn(
            Optional.of(new CrmAccount(accountId, pharmacyId, PlanNames.STARTER, "ACTIVE", NOW)));
    when(store.findAddonById(addonId))
        .thenReturn(Optional.of(new SaasAddon(addonId, "E_INVOICE", 19900, "e", true)));
    when(store.findActiveAccountAddon(accountId, addonId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.detachAddon(ops, accountId, addonId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ADDON_NOT_ATTACHED");
  }

  private static SaasPlan plan(
      UUID id, String name, long paise, Integer seats, Integer invoiceCap) {
    return new SaasPlan(id, name, paise, seats, invoiceCap, true, false, NOW);
  }

  private static final class RecordingAudit implements CrmAuditPort {
    final List<String> actions = new java.util.ArrayList<>();
    final List<Map<String, Object>> befores = new java.util.ArrayList<>();
    final List<Map<String, Object>> afters = new java.util.ArrayList<>();

    @Override
    public void append(
        String entityType,
        UUID actorId,
        String actorRole,
        UUID entityId,
        String action,
        Map<String, Object> before,
        Map<String, Object> after) {
      actions.add(action);
      befores.add(before);
      afters.add(after);
    }
  }
}

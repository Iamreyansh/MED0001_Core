package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.CrmAuditPort;
import com.nammamedmate.crm.application.port.out.SaasModuleUsageStore;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.domain.AccountModuleOverride;
import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.crm.domain.PlanSubscriber;
import com.nammamedmate.crm.domain.SaasAddon;
import com.nammamedmate.crm.domain.SaasPlan;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SaasPlanServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-15T10:00:00Z");

  @Mock SaasPlanStore store;
  @Mock SaasModuleUsageStore moduleUsage;
  @Mock CrmAuditPort audit;
  @Mock SubscriptionService subscriptions;
  SaasPlanService service;
  MedmatePrincipal superAdmin;
  UUID starterId;
  UUID pharmacyId;

  @BeforeEach
  void setUp() {
    service =
        new SaasPlanService(
            store, moduleUsage, audit, Clock.fixed(NOW, ZoneOffset.UTC), subscriptions);
    starterId = UUID.fromString("a1000000-0000-4000-8000-000000000002");
    pharmacyId = Ids.newId();
    superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  }

  @Test
  void getPlanAdminPaginationBranches() {
    when(store.findPlanById(starterId))
        .thenReturn(
            Optional.of(
                new SaasPlan(starterId, PlanNames.STARTER, 69900, 2, 500, true, false, NOW)));
    when(store.countActiveSubscribers(PlanNames.STARTER)).thenReturn(150L);
    when(store.listSubscribers(eq(PlanNames.STARTER), eq(0), eq(20)))
        .thenReturn(List.of(new PlanSubscriber(Ids.newId(), "P", NOW)));
    when(store.moduleCodesForPlan(PlanNames.STARTER)).thenReturn(List.of("INVENTORY"));

    Map<String, Object> data = service.getPlanAdmin(superAdmin, starterId, 0, 0);
    @SuppressWarnings("unchecked")
    Map<String, Object> list = (Map<String, Object>) data.get("subscriber_list");
    @SuppressWarnings("unchecked")
    Map<String, Object> meta = (Map<String, Object>) list.get("meta");
    assertThat(meta).containsEntry("page", 1).containsEntry("limit", 20);

    when(store.listSubscribers(eq(PlanNames.STARTER), eq(0), eq(100))).thenReturn(List.of());
    Map<String, Object> capped = service.getPlanAdmin(superAdmin, starterId, 1, 500);
    @SuppressWarnings("unchecked")
    Map<String, Object> list2 = (Map<String, Object>) capped.get("subscriber_list");
    @SuppressWarnings("unchecked")
    Map<String, Object> meta2 = (Map<String, Object>) list2.get("meta");
    assertThat(meta2).containsEntry("limit", 100).containsEntry("has_next", true);
  }

  @Test
  void updatePlanNullPrincipalAndSeatOnly() {
    assertThatThrownBy(() -> service.updatePlan(null, starterId, null, 1, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    SaasPlan before = new SaasPlan(starterId, PlanNames.STARTER, 69900, 2, 500, true, false, NOW);
    SaasPlan after = new SaasPlan(starterId, PlanNames.STARTER, 69900, 4, 500, true, false, NOW);
    when(store.findPlanById(starterId)).thenReturn(Optional.of(before));
    when(store.updatePlan(eq(starterId), eq(null), eq(4), eq(null), eq(NOW))).thenReturn(after);
    Map<String, Object> data = service.updatePlan(superAdmin, starterId, null, 4, null);
    assertThat(data).containsEntry("plan_id", starterId);
  }

  @Test
  void pharmacyOwnerNullPharmacyAndEnterpriseNoUpgrade() {
    MedmatePrincipal noPharmacy =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.listPlansForPharmacy(noPharmacy))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    MedmatePrincipal owner =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
    when(subscriptions.effectivePlanName(pharmacyId)).thenReturn(PlanNames.ENTERPRISE);
    UUID ent = UUID.fromString("a1000000-0000-4000-8000-000000000004");
    when(store.listActivePlans())
        .thenReturn(
            List.of(
                new SaasPlan(starterId, PlanNames.STARTER, 69900, 2, 500, true, false, NOW),
                new SaasPlan(ent, PlanNames.ENTERPRISE, 0, null, null, true, true, NOW)));
    when(store.moduleCodesForPlan(any())).thenReturn(List.of("INVENTORY"));
    Map<String, Object> data = service.listPlansForPharmacy(owner);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> plans = (List<Map<String, Object>>) data.get("plans");
    assertThat(plans.get(0)).doesNotContainKey("upgrade_cta");
    assertThat(plans.get(1)).containsEntry("is_current", true).doesNotContainKey("upgrade_cta");
  }

  @Test
  void planIncludesModuleNullModuleAndSuperWrite() {
    assertThat(service.planIncludesModule(PlanNames.STARTER, null)).isFalse();
    UUID accountId = Ids.newId();
    UUID addonId = Ids.newId();
    when(store.findAccountById(accountId))
        .thenReturn(
            Optional.of(new CrmAccount(accountId, pharmacyId, PlanNames.FREE, "ACTIVE", NOW)));
    when(store.findAddonById(addonId))
        .thenReturn(Optional.of(new SaasAddon(addonId, "E_INVOICE", 19900, "e", true)));
    when(store.findActiveAccountAddon(accountId, addonId)).thenReturn(Optional.empty());
    assertThat(service.attachAddon(superAdmin, accountId, addonId)).containsKey("message");
  }

  @Test
  void updateFreeSeatOnlyAndNullPharmacyPrincipal() {
    UUID freeId = UUID.fromString("a1000000-0000-4000-8000-000000000001");
    SaasPlan before = new SaasPlan(freeId, PlanNames.FREE, 0, 1, 100, true, false, NOW);
    SaasPlan after = new SaasPlan(freeId, PlanNames.FREE, 0, 2, 100, true, false, NOW);
    when(store.findPlanById(freeId)).thenReturn(Optional.of(before));
    when(store.updatePlan(eq(freeId), eq(null), eq(2), eq(null), eq(NOW))).thenReturn(after);
    assertThat(service.updatePlan(superAdmin, freeId, null, 2, null))
        .containsEntry("plan_id", freeId);

    assertThatThrownBy(() -> service.listPlansForPharmacy(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void detachAccountAndAddonMissing() {
    MedmatePrincipal ops =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    UUID accountId = Ids.newId();
    UUID addonId = Ids.newId();
    when(store.findAccountById(accountId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.detachAddon(ops, accountId, addonId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ACCOUNT_NOT_FOUND");
    when(store.findAccountById(accountId))
        .thenReturn(
            Optional.of(new CrmAccount(accountId, pharmacyId, PlanNames.FREE, "ACTIVE", NOW)));
    when(store.findAddonById(addonId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.detachAddon(ops, accountId, addonId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ADDON_NOT_FOUND");
  }

  @Test
  void moduleAccessibleOverrideBeatsPlan() {
    assertThat(service.moduleAccessibleForPharmacy(null, "mod_offers")).isFalse();
    assertThat(service.moduleAccessibleForPharmacy(pharmacyId, null)).isFalse();

    UUID accountId = Ids.newId();
    when(store.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(new CrmAccount(accountId, pharmacyId, PlanNames.FREE, "ACTIVE", NOW)));
    when(moduleUsage.findOverride(accountId, "mod_analytics_adv"))
        .thenReturn(
            Optional.of(
                new AccountModuleOverride(
                    Ids.newId(), accountId, "mod_analytics_adv", true, "beta", Ids.newId(), NOW)));
    assertThat(service.moduleAccessibleForPharmacy(pharmacyId, "mod_analytics_adv")).isTrue();

    when(moduleUsage.findOverride(accountId, "mod_offers")).thenReturn(Optional.empty());
    when(subscriptions.effectivePlanName(pharmacyId)).thenReturn(PlanNames.FREE);
    when(store.planIncludesModule(PlanNames.FREE, "mod_offers")).thenReturn(false);
    assertThat(service.moduleAccessibleForPharmacy(pharmacyId, "mod_offers")).isFalse();

    when(store.findAccountByPharmacyId(pharmacyId)).thenReturn(Optional.empty());
    when(subscriptions.effectivePlanName(pharmacyId)).thenReturn(PlanNames.STARTER);
    when(store.planIncludesModule(PlanNames.STARTER, "mod_khata")).thenReturn(true);
    assertThat(service.moduleAccessibleForPharmacy(pharmacyId, "mod_khata")).isTrue();
  }
}

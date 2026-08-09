package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.CrmModuleNudgeOutboxPort;
import com.nammamedmate.crm.application.port.out.SaasModuleUsageStore;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.domain.AccountModuleOverride;
import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.ModuleMatrixRow;
import com.nammamedmate.crm.domain.ModuleUsageMonthly;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.crm.domain.SaasPlan;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
class FeatureAdoptionServiceGapsTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final LocalDate MONTH = LocalDate.of(2026, 7, 1);

  @Mock SaasModuleUsageStore usage;
  @Mock SaasPlanStore plans;
  @Mock CrmModuleNudgeOutboxPort nudgeOutbox;
  FeatureAdoptionService service;
  MedmatePrincipal ops;
  MedmatePrincipal owner;

  @BeforeEach
  void setUp() {
    service =
        new FeatureAdoptionService(usage, plans, nudgeOutbox, Clock.fixed(NOW, ZoneOffset.UTC));
    ops = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "o");
    owner =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_OWNER, Ids.newId(), TokenScope.FULL, "p");
  }

  @Test
  void listModulesFiltersAndSorts() {
    when(usage.listModuleMatrix())
        .thenReturn(
            List.of(
                new ModuleMatrixRow(
                    Ids.newId(), "mod_a", "A", "A", "CORE", List.of(PlanNames.FREE)),
                new ModuleMatrixRow(
                    Ids.newId(), "mod_b", "B", "B", "ANALYTICS", List.of(PlanNames.RETAIL_PRO))));
    when(usage.countEligibleAccounts(any())).thenReturn(10L);
    when(usage.countAccountsUsing(any(), eq(MONTH))).thenReturn(1L, 5L);

    Map<String, Object> filtered =
        service.listModules(ops, "ANALYTICS", "RETAIL_PRO", "module", "asc");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> modules = (List<Map<String, Object>>) filtered.get("modules");
    assertThat(modules).hasSize(1);
    assertThat(modules.getFirst()).containsEntry("module_id", "mod_b");

    when(usage.countAccountsUsing(any(), eq(MONTH))).thenReturn(1L, 5L);
    Map<String, Object> byUsing = service.listModules(ops, null, null, "accounts_using", "asc");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> sorted = (List<Map<String, Object>>) byUsing.get("modules");
    assertThat(((Number) sorted.getFirst().get("accounts_using")).longValue()).isEqualTo(1L);

    assertThat(FeatureAdoptionService.lowestTier(List.of())).isEqualTo(PlanNames.FREE);
    assertThat(FeatureAdoptionService.lowestTier(List.of("GHOST"))).isEqualTo(PlanNames.FREE);
  }

  @Test
  void toggleValidationAndNotFound() {
    assertThatThrownBy(() -> service.toggleModule(ops, Ids.newId(), "mod_x", null, "r"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.toggleModule(ops, Ids.newId(), "mod_x", true, "  "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(usage.findModuleById("mod_x")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.toggleModule(ops, Ids.newId(), "mod_x", true, "r"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("MODULE_NOT_FOUND");
    when(usage.findModuleById("mod_x"))
        .thenReturn(
            Optional.of(
                new ModuleMatrixRow(Ids.newId(), "mod_x", "X", "X", "CORE", List.of("FREE"))));
    when(plans.findAccountById(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.toggleModule(ops, Ids.newId(), "mod_x", false, "hold"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ACCOUNT_NOT_FOUND");
  }

  @Test
  void recordUsageBestEffort() {
    service.recordUsage(null, "mod_billing");
    service.recordUsage(Ids.newId(), null);
    verify(plans, never()).findAccountByPharmacyId(any());

    UUID pharmacyId = Ids.newId();
    when(plans.findAccountByPharmacyId(pharmacyId)).thenReturn(Optional.empty());
    service.recordUsage(pharmacyId, "mod_billing");
    verify(usage, never()).incrementUsage(any(), any(), any(), any());

    UUID accountId = Ids.newId();
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(new CrmAccount(accountId, pharmacyId, PlanNames.FREE, "ACTIVE", NOW)));
    doThrow(new RuntimeException("db"))
        .when(usage)
        .incrementUsage(eq(accountId), eq("mod_billing"), eq(MONTH), eq(NOW));
    service.recordUsage(pharmacyId, "mod_billing");
  }

  @Test
  void usageSummaryIncludesOverrideModuleAndAuthGuards() {
    assertThatThrownBy(() -> service.listModules(null, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.getModule(owner, "mod_x"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.usageSummary(ops, Ids.newId()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ACCOUNT_NOT_FOUND");

    UUID accountId = Ids.newId();
    UUID pharmacyId = Ids.newId();
    when(plans.findAccountById(accountId))
        .thenReturn(Optional.of(new CrmAccount(accountId, pharmacyId, "GHOST", "ACTIVE", NOW)));
    when(plans.findPlanByName("GHOST")).thenReturn(Optional.empty());
    when(plans.findPlanByName(PlanNames.FREE)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.usageSummary(ops, accountId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");

    when(plans.findPlanByName(PlanNames.FREE))
        .thenReturn(
            Optional.of(new SaasPlan(Ids.newId(), PlanNames.FREE, 0, 1, 100, true, false, NOW)));
    when(plans.moduleCodesForPlan(PlanNames.FREE)).thenReturn(List.of("BILLING"));
    when(usage.countModulesUsedSince(eq(accountId), any())).thenReturn(1);
    when(usage.listAccountUsageMonth(eq(accountId), eq(MONTH)))
        .thenReturn(
            List.of(new ModuleUsageMonthly(Ids.newId(), accountId, "mod_billing", MONTH, 3, NOW)));
    when(usage.listModuleMatrix())
        .thenReturn(
            List.of(
                new ModuleMatrixRow(
                    Ids.newId(), "mod_billing", "Billing", "BILLING", "CORE", List.of("FREE")),
                new ModuleMatrixRow(
                    Ids.newId(),
                    "mod_analytics_adv",
                    "Adv",
                    "ADVANCED_ANALYTICS",
                    "ANALYTICS",
                    List.of("RETAIL_PRO"))));
    when(usage.findOverride(accountId, "mod_analytics_adv"))
        .thenReturn(
            Optional.of(
                new AccountModuleOverride(
                    Ids.newId(), accountId, "mod_analytics_adv", true, "beta", Ids.newId(), NOW)));
    when(usage.countActiveStaff(pharmacyId)).thenReturn(1L);
    when(usage.listActiveStaffNames(pharmacyId)).thenReturn(List.of("X"));
    when(usage.countInvoicesThisMonth(eq(pharmacyId), any(), any())).thenReturn(1L);
    when(usage.pharmacyName(pharmacyId)).thenReturn("P");
    when(usage.maxLastActive(accountId)).thenReturn(NOW);

    Map<String, Object> data = service.usageSummary(ops, accountId);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> events = (List<Map<String, Object>>) data.get("module_event_counts");
    assertThat(events).hasSize(2);
  }

  @Test
  void getModulePerAccountAndEmptyOverview() {
    when(usage.listModuleMatrix()).thenReturn(List.of());
    Map<String, Object> empty = service.listModules(ops, null, null, null, "desc");
    @SuppressWarnings("unchecked")
    Map<String, Object> chips = (Map<String, Object>) empty.get("chips");
    assertThat(chips).containsEntry("avg_adoption_pct", 0.0).containsEntry("top_module", null);

    when(usage.findModuleById("mod_billing"))
        .thenReturn(
            Optional.of(
                new ModuleMatrixRow(
                    Ids.newId(), "mod_billing", "Billing", "BILLING", "CORE", List.of("STARTER"))));
    when(usage.countEligibleAccounts("mod_billing")).thenReturn(1L);
    when(usage.countAccountsUsing("mod_billing", MONTH)).thenReturn(1L);
    when(usage.listEligibleNotUsing("mod_billing", MONTH)).thenReturn(List.of());
    when(usage.listPerAccountUsage("mod_billing", MONTH))
        .thenReturn(
            List.of(new SaasModuleUsageStore.AccountUsageRow(Ids.newId(), "Shop", 12, NOW)));
    Map<String, Object> detail = service.getModule(ops, "mod_billing");
    assertThat(detail).containsKey("per_account_event_counts");
  }
}

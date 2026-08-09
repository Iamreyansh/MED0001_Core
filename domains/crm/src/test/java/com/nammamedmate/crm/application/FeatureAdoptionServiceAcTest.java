package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.CrmModuleNudgeOutboxPort;
import com.nammamedmate.crm.application.port.out.SaasModuleUsageStore;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.domain.AccountModuleOverride;
import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.ModuleMatrixRow;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeatureAdoptionServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final LocalDate MONTH = LocalDate.of(2026, 7, 1);

  @Mock SaasModuleUsageStore usage;
  @Mock SaasPlanStore plans;
  @Mock CrmModuleNudgeOutboxPort nudgeOutbox;
  FeatureAdoptionService service;
  MedmatePrincipal superAdmin;
  MedmatePrincipal finance;
  MedmatePrincipal ops;

  @BeforeEach
  void setUp() {
    service =
        new FeatureAdoptionService(usage, plans, nudgeOutbox, Clock.fixed(NOW, ZoneOffset.UTC));
    superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    finance = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "f");
    ops = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "o");
  }

  @Test
  @DisplayName("AC-001/002 overview adoption_pct and low_adoption flag")
  void ac001_ac002_overview() {
    when(usage.listModuleMatrix())
        .thenReturn(
            List.of(
                row("mod_billing", "Billing", "BILLING", "CORE", List.of("STARTER", "RETAIL_PRO")),
                row(
                    "mod_analytics_adv",
                    "Advanced Analytics",
                    "ADVANCED_ANALYTICS",
                    "ANALYTICS",
                    List.of("RETAIL_PRO"))));
    when(usage.countEligibleAccounts("mod_billing")).thenReturn(600L);
    when(usage.countAccountsUsing("mod_billing", MONTH)).thenReturn(540L);
    when(usage.countEligibleAccounts("mod_analytics_adv")).thenReturn(180L);
    when(usage.countAccountsUsing("mod_analytics_adv", MONTH)).thenReturn(28L);

    Map<String, Object> data = service.listModules(finance, null, null, "adoption_pct", "desc");
    @SuppressWarnings("unchecked")
    Map<String, Object> chips = (Map<String, Object>) data.get("chips");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> modules = (List<Map<String, Object>>) data.get("modules");

    assertThat(chips).containsEntry("module_count", 2).containsEntry("low_adoption_count", 1);
    assertThat(modules.get(0))
        .containsEntry("module_id", "mod_billing")
        .containsEntry("adoption_pct", 90.0)
        .containsEntry("low_adoption", false);
    assertThat(modules.get(1))
        .containsEntry("adoption_pct", 15.6)
        .containsEntry("low_adoption", true);
  }

  @Test
  @DisplayName("AC-003/004 toggle override ON for non-RETAIL_PRO account")
  void ac003_ac004_toggleOverride() {
    UUID accountId = Ids.newId();
    when(usage.findModuleById("mod_analytics_adv"))
        .thenReturn(
            Optional.of(
                row(
                    "mod_analytics_adv",
                    "Advanced Analytics",
                    "ADVANCED_ANALYTICS",
                    "ANALYTICS",
                    List.of("RETAIL_PRO"))));
    when(plans.findAccountById(accountId))
        .thenReturn(
            Optional.of(new CrmAccount(accountId, Ids.newId(), PlanNames.STARTER, "ACTIVE", NOW)));
    AccountModuleOverride ov =
        new AccountModuleOverride(
            Ids.newId(), accountId, "mod_analytics_adv", true, "Beta access", ops.subject(), NOW);
    when(usage.upsertOverride(
            eq(accountId),
            eq("mod_analytics_adv"),
            eq(true),
            eq("Beta access"),
            eq(ops.subject()),
            eq(NOW)))
        .thenReturn(ov);

    Map<String, Object> data =
        service.toggleModule(ops, accountId, "mod_analytics_adv", true, "Beta access");
    assertThat(data)
        .containsEntry("enabled", true)
        .containsEntry("override", true)
        .containsEntry("reason", "Beta access")
        .containsEntry("toggled_by", ops.subject());
  }

  @Test
  @DisplayName("AC-005 nudge eligible-not-using with 0 events in 30d")
  void ac005_nudge() {
    when(usage.findModuleById("mod_analytics_adv"))
        .thenReturn(
            Optional.of(
                row(
                    "mod_analytics_adv",
                    "Advanced Analytics",
                    "ADVANCED_ANALYTICS",
                    "ANALYTICS",
                    List.of("RETAIL_PRO"))));
    UUID a1 = Ids.newId();
    UUID a2 = Ids.newId();
    when(usage.listNudgeTargetAccountIds(eq("mod_analytics_adv"), any()))
        .thenReturn(List.of(a1, a2));

    Map<String, Object> data = service.nudgeIneligible(ops, "mod_analytics_adv", "EMAIL");
    assertThat(data)
        .containsEntry("nudge_sent_count", 2)
        .containsEntry("eligible_not_using_count", 2)
        .containsEntry("channel", "EMAIL");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    verify(nudgeOutbox).publish(eq("crm.module.nudge"), any(), payload.capture());
    assertThat(payload.getValue()).containsEntry("module_id", "mod_analytics_adv");
    @SuppressWarnings("unchecked")
    List<UUID> ids = (List<UUID>) payload.getValue().get("account_ids");
    assertThat(ids).containsExactly(a1, a2);
  }

  @Test
  @DisplayName("AC-006 adoption_score FLOOR(5/8*100)=62")
  void ac006_adoptionScore() {
    UUID accountId = Ids.newId();
    UUID pharmacyId = Ids.newId();
    when(plans.findAccountById(accountId))
        .thenReturn(
            Optional.of(
                new CrmAccount(accountId, pharmacyId, PlanNames.RETAIL_PRO, "ACTIVE", NOW)));
    when(plans.findPlanByName(PlanNames.RETAIL_PRO))
        .thenReturn(
            Optional.of(
                new SaasPlan(
                    Ids.newId(), PlanNames.RETAIL_PRO, 149900, 5, null, true, false, NOW)));
    when(plans.moduleCodesForPlan(PlanNames.RETAIL_PRO))
        .thenReturn(List.of("A", "B", "C", "D", "E", "F", "G", "H"));
    when(usage.countModulesUsedSince(eq(accountId), any())).thenReturn(5);
    when(usage.listAccountUsageMonth(eq(accountId), eq(MONTH))).thenReturn(List.of());
    when(usage.listModuleMatrix()).thenReturn(List.of());
    when(usage.countActiveStaff(pharmacyId)).thenReturn(2L);
    when(usage.listActiveStaffNames(pharmacyId)).thenReturn(List.of("Ramesh", "Priya"));
    when(usage.countInvoicesThisMonth(eq(pharmacyId), any(), any())).thenReturn(187L);
    when(usage.pharmacyName(pharmacyId)).thenReturn("Apollo");
    when(usage.maxLastActive(accountId)).thenReturn(NOW);

    Map<String, Object> data = service.usageSummary(ops, accountId);
    assertThat(data).containsEntry("adoption_score", 62);
  }

  @Test
  @DisplayName("AC-007 monthly bucket reset — new month starts at count 0 (archive retained)")
  void ac007_monthlyBucket() {
    UUID pharmacyId = Ids.newId();
    UUID accountId = Ids.newId();
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(new CrmAccount(accountId, pharmacyId, PlanNames.STARTER, "ACTIVE", NOW)));
    service.recordUsage(pharmacyId, "mod_billing");
    verify(usage).incrementUsage(accountId, "mod_billing", MONTH, NOW);
  }

  @Test
  @DisplayName("AC-008 seat_usage counts active staff only")
  void ac008_seatUsage() {
    UUID accountId = Ids.newId();
    UUID pharmacyId = Ids.newId();
    when(plans.findAccountById(accountId))
        .thenReturn(
            Optional.of(new CrmAccount(accountId, pharmacyId, PlanNames.STARTER, "ACTIVE", NOW)));
    when(plans.findPlanByName(PlanNames.STARTER))
        .thenReturn(
            Optional.of(
                new SaasPlan(Ids.newId(), PlanNames.STARTER, 69900, 2, 500, true, false, NOW)));
    when(plans.moduleCodesForPlan(PlanNames.STARTER)).thenReturn(List.of("BILLING"));
    when(usage.countModulesUsedSince(eq(accountId), any())).thenReturn(0);
    when(usage.listAccountUsageMonth(eq(accountId), eq(MONTH))).thenReturn(List.of());
    when(usage.listModuleMatrix())
        .thenReturn(List.of(row("mod_billing", "Billing", "BILLING", "CORE", List.of("STARTER"))));
    when(usage.countActiveStaff(pharmacyId)).thenReturn(2L);
    when(usage.listActiveStaffNames(pharmacyId)).thenReturn(List.of("A", "B"));
    when(usage.countInvoicesThisMonth(eq(pharmacyId), any(), any())).thenReturn(0L);
    when(usage.pharmacyName(pharmacyId)).thenReturn("Shop");
    when(usage.maxLastActive(accountId)).thenReturn(null);

    @SuppressWarnings("unchecked")
    Map<String, Object> seats =
        (Map<String, Object>) service.usageSummary(ops, accountId).get("seat_usage");
    assertThat(seats).containsEntry("used", 2L).containsEntry("limit", 2);
  }

  @Test
  @DisplayName("AC-009 invoice_usage counts ERP invoices this calendar month")
  void ac009_invoiceUsage() {
    UUID accountId = Ids.newId();
    UUID pharmacyId = Ids.newId();
    when(plans.findAccountById(accountId))
        .thenReturn(
            Optional.of(new CrmAccount(accountId, pharmacyId, PlanNames.STARTER, "ACTIVE", NOW)));
    when(plans.findPlanByName(PlanNames.STARTER))
        .thenReturn(
            Optional.of(
                new SaasPlan(Ids.newId(), PlanNames.STARTER, 69900, 2, 500, true, false, NOW)));
    when(plans.moduleCodesForPlan(PlanNames.STARTER)).thenReturn(List.of());
    when(usage.countModulesUsedSince(eq(accountId), any())).thenReturn(0);
    when(usage.listAccountUsageMonth(eq(accountId), eq(MONTH))).thenReturn(List.of());
    when(usage.listModuleMatrix()).thenReturn(List.of());
    when(usage.countActiveStaff(pharmacyId)).thenReturn(0L);
    when(usage.listActiveStaffNames(pharmacyId)).thenReturn(List.of());
    when(usage.countInvoicesThisMonth(eq(pharmacyId), any(), any())).thenReturn(187L);
    when(usage.pharmacyName(pharmacyId)).thenReturn("Shop");
    when(usage.maxLastActive(accountId)).thenReturn(null);

    @SuppressWarnings("unchecked")
    Map<String, Object> inv =
        (Map<String, Object>) service.usageSummary(ops, accountId).get("invoice_usage_this_month");
    assertThat(inv).containsEntry("used", 187L).containsEntry("limit", 500);
  }

  @Test
  @DisplayName("AC-010 eligible_not_using sorted by pharmacy name")
  void ac010_eligibleNotUsingSorted() {
    when(usage.findModuleById("mod_analytics_adv"))
        .thenReturn(
            Optional.of(
                row(
                    "mod_analytics_adv",
                    "Advanced Analytics",
                    "ADVANCED_ANALYTICS",
                    "ANALYTICS",
                    List.of("RETAIL_PRO"))));
    when(usage.countEligibleAccounts("mod_analytics_adv")).thenReturn(2L);
    when(usage.countAccountsUsing("mod_analytics_adv", MONTH)).thenReturn(0L);
    when(usage.listEligibleNotUsing("mod_analytics_adv", MONTH))
        .thenReturn(
            List.of(
                new SaasModuleUsageStore.EligibleAccountRow(
                    Ids.newId(), "Apollo Pharmacy HSR", true, 0, null),
                new SaasModuleUsageStore.EligibleAccountRow(
                    Ids.newId(), "Zara Meds", true, 0, null)));
    when(usage.listPerAccountUsage("mod_analytics_adv", MONTH)).thenReturn(List.of());

    Map<String, Object> data = service.getModule(ops, "mod_analytics_adv");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> notUsing = (List<Map<String, Object>>) data.get("eligible_not_using");
    assertThat(notUsing.get(0)).containsEntry("pharmacy_name", "Apollo Pharmacy HSR");
    assertThat(notUsing.get(1)).containsEntry("pharmacy_name", "Zara Meds");
  }

  @Test
  void nudgeErrorsAndFinanceForbiddenOnToggle() {
    when(usage.findModuleById("missing")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.nudgeIneligible(ops, "missing", "EMAIL"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("MODULE_NOT_FOUND");

    when(usage.findModuleById("mod_billing"))
        .thenReturn(
            Optional.of(row("mod_billing", "Billing", "BILLING", "CORE", List.of("STARTER"))));
    when(usage.listNudgeTargetAccountIds(eq("mod_billing"), any())).thenReturn(List.of());
    assertThatThrownBy(() -> service.nudgeIneligible(ops, "mod_billing", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NO_ELIGIBLE_ACCOUNTS");

    assertThatThrownBy(() -> service.toggleModule(finance, Ids.newId(), "mod_billing", true, "x"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    verify(nudgeOutbox, never()).publish(any(), any(), any());
  }

  private static ModuleMatrixRow row(
      String id, String name, String code, String group, List<String> plans) {
    return new ModuleMatrixRow(Ids.newId(), id, name, code, group, plans);
  }
}

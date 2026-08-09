package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.BusinessPerformancePort;
import com.nammamedmate.crm.application.port.out.CrmHealthOutboxPort;
import com.nammamedmate.crm.application.port.out.SaasAccountHealthStore;
import com.nammamedmate.crm.application.port.out.SaasInvoiceStore;
import com.nammamedmate.crm.application.port.out.SaasModuleUsageStore;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.application.port.out.SupportSatisfactionPort;
import com.nammamedmate.crm.domain.AccountHealthScore;
import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.HealthBand;
import com.nammamedmate.crm.domain.InvoiceStatus;
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
class AccountHealthServiceGapsTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  @Mock SaasAccountHealthStore health;
  @Mock SaasPlanStore plans;
  @Mock SaasModuleUsageStore usage;
  @Mock SaasInvoiceStore invoices;
  @Mock SupportSatisfactionPort support;
  @Mock BusinessPerformancePort business;
  @Mock CrmHealthOutboxPort outbox;

  AccountHealthService service;
  MedmatePrincipal ops;
  UUID accountId;
  UUID pharmacyId;
  CrmAccount account;

  @BeforeEach
  void setUp() {
    service =
        new AccountHealthService(
            health,
            plans,
            usage,
            invoices,
            support,
            business,
            outbox,
            Clock.fixed(NOW, ZoneOffset.UTC));
    ops = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "o");
    accountId = Ids.newId();
    pharmacyId = Ids.newId();
    account = new CrmAccount(accountId, pharmacyId, "WEIRD", "ACTIVE", NOW);
  }

  @Test
  void planFallbackAndMissingPlan() {
    when(plans.findAccountById(accountId)).thenReturn(Optional.of(account));
    when(plans.findPlanByName("WEIRD")).thenReturn(Optional.empty());
    when(plans.findPlanByName(PlanNames.FREE)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getHealth(ops, accountId))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PLAN_NOT_FOUND");

    SaasPlan free = new SaasPlan(Ids.newId(), PlanNames.FREE, 0, 1, 100, true, false, NOW);
    when(plans.findPlanByName(PlanNames.FREE)).thenReturn(Optional.of(free));
    when(plans.moduleCodesForPlan(PlanNames.FREE)).thenReturn(List.of("BILLING"));
    when(usage.countModulesUsedSince(eq(accountId), any())).thenReturn(1);
    when(invoices.listOpenStatuses(accountId)).thenReturn(List.of(InvoiceStatus.DUE));
    when(support.scoreForAccount(accountId)).thenReturn(100.0);
    when(business.scoreForAccount(accountId, pharmacyId)).thenReturn(70.0);
    when(health.findByAccountId(accountId)).thenReturn(Optional.empty());
    when(usage.pharmacyName(pharmacyId)).thenReturn("Shop");
    Map<String, Object> data = service.getHealth(ops, accountId);
    assertThat(data.get("health_band"))
        .isIn(HealthBand.MODERATE, HealthBand.HEALTHY, HealthBand.AT_RISK);
  }

  @Test
  void usagePlanFallbackAndEmptyModules() {
    when(plans.findAccountById(accountId)).thenReturn(Optional.of(account));
    when(plans.findPlanByName("WEIRD")).thenReturn(Optional.empty());
    when(plans.findPlanByName(PlanNames.FREE)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getUsage(ops, accountId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PLAN_NOT_FOUND");

    SaasPlan free = new SaasPlan(Ids.newId(), PlanNames.FREE, 0, 1, 100, true, false, NOW);
    when(plans.findPlanByName(PlanNames.FREE)).thenReturn(Optional.of(free));
    when(plans.moduleCodesForPlan(PlanNames.FREE)).thenReturn(List.of("BILLING"));
    when(usage.listModuleMatrix())
        .thenReturn(
            List.of(
                new ModuleMatrixRow(
                    Ids.newId(), "mod_x", "X", "OTHER", "CORE", List.of("ENTERPRISE")),
                new ModuleMatrixRow(
                    Ids.newId(), "mod_billing", "Billing", "BILLING", "CORE", List.of("FREE"))));
    when(usage.listAccountUsageMonth(eq(accountId), any())).thenReturn(List.of());
    Map<String, Object> data = service.getUsage(ops, accountId);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> modules = (List<Map<String, Object>>) data.get("modules");
    assertThat(modules).hasSize(1);
    assertThat(data.get("overall_last_active_at")).isNull();
  }

  @Test
  void usagePriorMonthAndLaterOverall() {
    CrmAccount starter = new CrmAccount(accountId, pharmacyId, PlanNames.STARTER, "ACTIVE", NOW);
    SaasPlan plan = new SaasPlan(Ids.newId(), PlanNames.STARTER, 69900, 2, 500, true, false, NOW);
    when(plans.findAccountById(accountId)).thenReturn(Optional.of(starter));
    when(plans.findPlanByName(PlanNames.STARTER)).thenReturn(Optional.of(plan));
    when(plans.moduleCodesForPlan(PlanNames.STARTER))
        .thenReturn(List.of("BILLING", "INVENTORY", "REPORTS"));
    when(usage.listModuleMatrix())
        .thenReturn(
            List.of(
                new ModuleMatrixRow(
                    Ids.newId(), "mod_billing", "Billing", "BILLING", "CORE", List.of("STARTER")),
                new ModuleMatrixRow(
                    Ids.newId(), "mod_inv", "Inventory", "INVENTORY", "CORE", List.of("STARTER")),
                new ModuleMatrixRow(
                    Ids.newId(), "mod_reports", "Reports", "REPORTS", "CORE", List.of("STARTER"))));
    Instant older = NOW.minusSeconds(86400);
    Instant newer = NOW.plusSeconds(3600);
    Instant mid = NOW;
    Instant tooOld = NOW.minus(40, java.time.temporal.ChronoUnit.DAYS);
    when(usage.listAccountUsageMonth(eq(accountId), eq(LocalDate.of(2026, 7, 1))))
        .thenReturn(
            List.of(
                new ModuleUsageMonthly(
                    Ids.newId(), accountId, "mod_billing", LocalDate.of(2026, 7, 1), 10, older),
                new ModuleUsageMonthly(
                    Ids.newId(), accountId, "mod_inv", LocalDate.of(2026, 7, 1), 3, newer),
                new ModuleUsageMonthly(
                    Ids.newId(), accountId, "mod_reports", LocalDate.of(2026, 7, 1), 1, mid)));
    when(usage.listAccountUsageMonth(eq(accountId), eq(LocalDate.of(2026, 6, 1))))
        .thenReturn(List.of());
    Map<String, Object> data = service.getUsage(ops, accountId);
    assertThat(data.get("overall_last_active_at")).isEqualTo(newer);

    // zero-count / null last_active with events / stale last_active day buckets
    when(usage.listAccountUsageMonth(eq(accountId), eq(LocalDate.of(2026, 7, 1))))
        .thenReturn(
            List.of(
                new ModuleUsageMonthly(
                    Ids.newId(), accountId, "mod_billing", LocalDate.of(2026, 7, 1), 0, null),
                new ModuleUsageMonthly(
                    Ids.newId(), accountId, "mod_inv", LocalDate.of(2026, 7, 1), 5, null),
                new ModuleUsageMonthly(
                    Ids.newId(), accountId, "mod_reports", LocalDate.of(2026, 7, 1), 2, tooOld)));
    Map<String, Object> stale = service.getUsage(ops, accountId);
    assertThat(stale.get("overall_last_active_at")).isEqualTo(tooOld);
  }

  @Test
  void listAtRiskPagingAndBandFilterAndExistingScore() {
    when(health.listAtRisk(eq(HealthBand.CHURNING), eq(20), eq(10))).thenReturn(List.of());
    when(health.countAtRisk(HealthBand.CHURNING)).thenReturn(0L);
    when(health.sumMrrAtRiskPaise()).thenReturn(0L);
    AccountHealthService.PagedResult r = service.listAtRisk(ops, "CHURNING", 3, 10);
    assertThat(r.meta().page()).isEqualTo(3);
    assertThat(r.meta().limit()).isEqualTo(10);

    AccountHealthScore existing =
        new AccountHealthScore(
            Ids.newId(),
            accountId,
            88,
            90,
            100,
            100,
            70,
            HealthBand.HEALTHY,
            List.of(),
            List.of("Maintain regular CSM check-in"),
            NOW);
    when(plans.findAccountById(accountId))
        .thenReturn(
            Optional.of(new CrmAccount(accountId, pharmacyId, PlanNames.STARTER, "ACTIVE", NOW)));
    when(health.findByAccountId(accountId)).thenReturn(Optional.of(existing));
    when(usage.pharmacyName(pharmacyId)).thenReturn("P");
    Map<String, Object> healthData = service.getHealth(ops, accountId);
    assertThat(healthData.get("overall_score")).isEqualTo(88.0);
    verify(health, org.mockito.Mockito.never()).upsert(any());
  }

  @Test
  void savePlayBlankNotesAndSchedulerPath() {
    when(plans.findAccountById(accountId)).thenReturn(Optional.of(account));
    when(health.insertSavePlay(any())).thenAnswer(inv -> inv.getArgument(0));
    Map<String, Object> data = service.logSavePlay(ops, accountId, "EMAIL", "ok", "  ");
    assertThat(data.get("action_type")).isEqualTo("EMAIL");
    MedmatePrincipal superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    Map<String, Object> withNotes =
        service.logSavePlay(superAdmin, accountId, "TRAINING", "done", "  note  ");
    assertThat(withNotes.get("action_type")).isEqualTo("TRAINING");
    service.logSavePlay(ops, accountId, "CALL", "done", null);
    assertThatThrownBy(() -> service.logSavePlay(ops, accountId, "CALL", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    when(plans.listActiveAccounts()).thenReturn(List.of());
    service.recomputeAll();
  }

  @Test
  void healthKpisAndInvalidBandIgnored() {
    when(health.listAtRisk(null, 0, 20)).thenReturn(List.of());
    when(health.countAtRisk(null)).thenReturn(0L);
    when(health.sumMrrAtRiskPaise()).thenReturn(0L);
    service.listAtRisk(ops, "HEALTHY", -1, 0);
    when(health.kpis())
        .thenReturn(new SaasAccountHealthStore.HealthKpis(0, 0, 0, 0, 0, 0, 0, null));
    assertThat(service.healthKpis(ops).get("computed_at")).isNull();
    assertThat(new AccountHealthService.PagedResult(null, null).data()).isEmpty();
  }
}

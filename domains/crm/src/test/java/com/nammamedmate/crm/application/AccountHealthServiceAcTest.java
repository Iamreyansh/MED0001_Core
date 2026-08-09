package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import com.nammamedmate.crm.domain.SavePlay;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
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
class AccountHealthServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  @Mock SaasAccountHealthStore health;
  @Mock SaasPlanStore plans;
  @Mock SaasModuleUsageStore usage;
  @Mock SaasInvoiceStore invoices;
  @Mock SupportSatisfactionPort support;
  @Mock BusinessPerformancePort business;
  @Mock CrmHealthOutboxPort outbox;

  AccountHealthService service;
  MedmatePrincipal superAdmin;
  MedmatePrincipal ops;
  MedmatePrincipal finance;
  UUID accountId;
  UUID pharmacyId;
  CrmAccount account;
  SaasPlan retailPro;

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
    superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    ops = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "o");
    finance = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "f");
    accountId = Ids.newId();
    pharmacyId = Ids.newId();
    account = new CrmAccount(accountId, pharmacyId, PlanNames.RETAIL_PRO, "ACTIVE", NOW);
    retailPro = new SaasPlan(Ids.newId(), PlanNames.RETAIL_PRO, 149900, 5, null, true, false, NOW);
  }

  @Test
  @DisplayName("AC-001 healthy account scores >= 75 HEALTHY")
  void ac001_healthy() {
    stubCompute(8, 8, List.of(), 100.0, 100.0);
    when(health.findByAccountId(accountId)).thenReturn(Optional.empty());
    when(usage.pharmacyName(pharmacyId)).thenReturn("Apollo Pharmacy HSR");

    Map<String, Object> data = service.getHealth(finance, accountId);
    assertThat(data.get("overall_score")).isEqualTo(100.0);
    assertThat(data.get("health_band")).isEqualTo(HealthBand.HEALTHY);
    @SuppressWarnings("unchecked")
    Map<String, Object> components = (Map<String, Object>) data.get("components");
    assertThat(components)
        .containsEntry("product_usage", 100.0)
        .containsEntry("billing_health", 100.0)
        .containsEntry("support_satisfaction", 100.0)
        .containsEntry("business_performance", 100.0);
  }

  @Test
  @DisplayName("AC-002 OVERDUE invoice sets billing_health=0 and band AT_RISK")
  void ac002_overdueAtRisk() {
    stubCompute(2, 8, List.of(InvoiceStatus.OVERDUE), 40.0, 40.0);
    when(health.findByAccountId(accountId)).thenReturn(Optional.empty());
    when(usage.pharmacyName(pharmacyId)).thenReturn("Apollo");

    Map<String, Object> data = service.getHealth(ops, accountId);
    @SuppressWarnings("unchecked")
    Map<String, Object> components = (Map<String, Object>) data.get("components");
    assertThat(components.get("billing_health")).isEqualTo(0.0);
    assertThat(((Number) data.get("overall_score")).doubleValue()).isLessThan(50.0);
    assertThat(data.get("health_band")).isEqualTo(HealthBand.AT_RISK);
  }

  @Test
  @DisplayName("AC-003 at-risk list score<50 sorted by mrr_rs desc")
  void ac003_atRiskList() {
    UUID a1 = Ids.newId();
    UUID a2 = Ids.newId();
    when(health.listAtRisk(null, 0, 20))
        .thenReturn(
            List.of(
                new SaasAccountHealthStore.AtRiskRow(
                    a1,
                    "High MRR",
                    PlanNames.RETAIL_PRO,
                    149900,
                    42,
                    HealthBand.AT_RISK,
                    LocalDate.of(2026, 8, 15),
                    null,
                    null),
                new SaasAccountHealthStore.AtRiskRow(
                    a2,
                    "Low MRR",
                    PlanNames.STARTER,
                    69900,
                    30,
                    HealthBand.AT_RISK,
                    LocalDate.of(2026, 8, 1),
                    null,
                    null)));
    when(health.countAtRisk(null)).thenReturn(2L);
    when(health.sumMrrAtRiskPaise()).thenReturn(219800L);

    AccountHealthService.PagedResult result = service.listAtRisk(finance, null, null, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> accounts = (List<Map<String, Object>>) result.data().get("accounts");
    assertThat(accounts).hasSize(2);
    assertThat(accounts.get(0).get("mrr_rs")).isEqualTo(new BigDecimal("1499.00"));
    assertThat(accounts.get(1).get("mrr_rs")).isEqualTo(new BigDecimal("699.00"));
    assertThat(((Number) accounts.get(0).get("overall_score")).doubleValue()).isLessThan(50);
    assertThat(result.data().get("total_mrr_at_risk_rs")).isEqualTo(new BigDecimal("2198.00"));
  }

  @Test
  @DisplayName("AC-004 save play logged updates last_save_play_at")
  void ac004_savePlay() {
    when(plans.findAccountById(accountId)).thenReturn(Optional.of(account));
    Instant logged = NOW;
    when(health.insertSavePlay(any()))
        .thenAnswer(
            inv -> {
              SavePlay p = inv.getArgument(0);
              return p;
            });

    Map<String, Object> data =
        service.logSavePlay(ops, accountId, "CALL", "Owner agreed to training", "Spoke 25m");
    assertThat(data)
        .containsEntry("account_id", accountId)
        .containsEntry("action_type", "CALL")
        .containsEntry("logged_by", ops.subject())
        .containsEntry("logged_at", logged);
    assertThat(data.get("save_play_id")).isNotNull();

    when(health.listAtRisk(null, 0, 20))
        .thenReturn(
            List.of(
                new SaasAccountHealthStore.AtRiskRow(
                    accountId,
                    "Apollo",
                    PlanNames.RETAIL_PRO,
                    149900,
                    42,
                    HealthBand.AT_RISK,
                    LocalDate.of(2026, 8, 15),
                    logged,
                    null)));
    when(health.countAtRisk(null)).thenReturn(1L);
    when(health.sumMrrAtRiskPaise()).thenReturn(149900L);
    AccountHealthService.PagedResult list = service.listAtRisk(ops, null, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> accounts = (List<Map<String, Object>>) list.data().get("accounts");
    assertThat(accounts.getFirst().get("last_save_play_at")).isEqualTo(logged);
  }

  @Test
  @DisplayName("AC-005 score < 40 auto-triggers save play outbox notification")
  @SuppressWarnings("unchecked")
  void ac005_savePlayNotify() {
    stubRecompute(0, 8, List.of(InvoiceStatus.OVERDUE), 20.0, 20.0);
    when(health.findByAccountId(accountId)).thenReturn(Optional.empty());
    when(plans.listActiveAccounts()).thenReturn(List.of(account));

    service.recomputeAll();

    ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    verify(outbox)
        .publish(eq(AccountHealthService.SAVE_PLAY_EVENT), eq(accountId), payload.capture());
    assertThat(payload.getValue()).containsKeys("account_id", "overall_score");
    assertThat(((Number) payload.getValue().get("overall_score")).doubleValue()).isLessThan(40);
  }

  @Test
  @DisplayName("AC-006 health KPIs at_risk_count matches at-risk list total")
  void ac006_kpiMatchesList() {
    when(health.countAtRisk(null)).thenReturn(32L);
    when(health.sumMrrAtRiskPaise()).thenReturn(4827000L);
    when(health.listAtRisk(null, 0, 20)).thenReturn(List.of());
    when(health.kpis())
        .thenReturn(
            new SaasAccountHealthStore.HealthKpis(68.4, 62.0, 24.0, 32, 8, 4827000L, 18, NOW));

    Map<String, Object> kpis = service.healthKpis(finance);
    AccountHealthService.PagedResult list = service.listAtRisk(finance, null, 1, 20);
    assertThat(kpis.get("at_risk_count")).isEqualTo(32L);
    assertThat(list.meta().total()).isEqualTo(32L);
  }

  @Test
  @DisplayName("AC-007 usage chart returns per-module events for last 30 days")
  void ac007_usageChart() {
    when(plans.findAccountById(accountId)).thenReturn(Optional.of(account));
    when(plans.findPlanByName(PlanNames.RETAIL_PRO)).thenReturn(Optional.of(retailPro));
    when(plans.moduleCodesForPlan(PlanNames.RETAIL_PRO)).thenReturn(List.of("BILLING"));
    when(usage.listModuleMatrix())
        .thenReturn(
            List.of(
                new ModuleMatrixRow(
                    Ids.newId(),
                    "mod_billing",
                    "Billing",
                    "BILLING",
                    "CORE",
                    List.of("RETAIL_PRO"))));
    LocalDate month = LocalDate.of(2026, 7, 1);
    when(usage.listAccountUsageMonth(eq(accountId), any()))
        .thenReturn(
            List.of(new ModuleUsageMonthly(Ids.newId(), accountId, "mod_billing", month, 45, NOW)));

    Map<String, Object> data = service.getUsage(ops, accountId);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> modules = (List<Map<String, Object>>) data.get("modules");
    assertThat(modules).hasSize(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> days =
        (List<Map<String, Object>>) modules.getFirst().get("events_per_day");
    assertThat(days).hasSize(30);
    assertThat(days.stream().mapToInt(d -> ((Number) d.get("count")).intValue()).sum())
        .isEqualTo(45);
    assertThat(data.get("overall_last_active_at")).isEqualTo(NOW);
  }

  @Test
  @DisplayName("AC-008 recomputed computed_at is within 24 hours")
  void ac008_computedAtFresh() {
    stubCompute(8, 8, List.of(), 100.0, 100.0);
    when(health.findByAccountId(accountId)).thenReturn(Optional.empty());
    when(usage.pharmacyName(pharmacyId)).thenReturn("Apollo");

    Map<String, Object> data = service.getHealth(superAdmin, accountId);
    Instant computed = (Instant) data.get("computed_at");
    assertThat(computed).isEqualTo(NOW);
    assertThat(ChronoWithin24h(computed, NOW)).isTrue();
  }

  @Test
  @DisplayName("AC-009 health_band correctly maps score ranges")
  void ac009_bandsViaRecompute() {
    assertBand(100, 100, 100, 100, HealthBand.HEALTHY);
    assertBand(60, 70, 60, 60, HealthBand.MODERATE);
    assertBand(40, 0, 40, 40, HealthBand.AT_RISK);
    assertBand(0, 0, 0, 0, HealthBand.CHURNING);
  }

  @Test
  @DisplayName("AC-010 mrr_at_risk_rs sums AT_RISK + CHURNING MRR")
  void ac010_mrrAtRisk() {
    when(health.kpis())
        .thenReturn(
            new SaasAccountHealthStore.HealthKpis(68.4, 62.0, 24.0, 32, 8, 4827000L, 18, NOW));
    Map<String, Object> kpis = service.healthKpis(superAdmin);
    assertThat(kpis.get("mrr_at_risk_rs")).isEqualTo(new BigDecimal("48270.00"));
    long manual = 149900L * 20 + 69900L * 12;
    when(health.kpis())
        .thenReturn(new SaasAccountHealthStore.HealthKpis(50, 40, 20, 20, 8, manual, 5, NOW));
    assertThat(service.healthKpis(finance).get("mrr_at_risk_rs"))
        .isEqualTo(new BigDecimal("38368.00"));
  }

  @Test
  void errorsAndAuth() {
    when(plans.findAccountById(accountId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getHealth(finance, accountId))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ACCOUNT_NOT_FOUND");
    assertThatThrownBy(() -> service.logSavePlay(ops, accountId, "SMS", "x", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ACCOUNT_NOT_FOUND");

    when(plans.findAccountById(accountId)).thenReturn(Optional.of(account));
    assertThatThrownBy(() -> service.logSavePlay(ops, accountId, "SMS", "x", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ACTION_TYPE");
    assertThatThrownBy(() -> service.logSavePlay(ops, accountId, "CALL", " ", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.getUsage(finance, accountId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.getHealth(null, accountId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    MedmatePrincipal support =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "s");
    assertThatThrownBy(() -> service.listAtRisk(support, null, 0, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.logSavePlay(finance, accountId, "CALL", "ok", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.logSavePlay(null, accountId, "CALL", "ok", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void noRepeatSavePlayNotifyWhenAlreadyBelow40() {
    stubRecompute(0, 8, List.of(InvoiceStatus.OVERDUE), 10.0, 10.0);
    when(health.findByAccountId(accountId))
        .thenReturn(
            Optional.of(
                new AccountHealthScore(
                    Ids.newId(),
                    accountId,
                    30,
                    0,
                    0,
                    10,
                    10,
                    HealthBand.AT_RISK,
                    List.of(),
                    List.of(),
                    NOW.minusSeconds(3600))));
    service.recomputeOne(account);
    verify(outbox, never()).publish(any(), any(), any());
  }

  private void assertBand(
      double usagePct, double billing, double supportScore, double businessScore, String band) {
    int modulesUsed = (int) Math.round(usagePct * 8 / 100.0);
    when(plans.findAccountById(accountId)).thenReturn(Optional.of(account));
    when(plans.findPlanByName(PlanNames.RETAIL_PRO)).thenReturn(Optional.of(retailPro));
    when(plans.moduleCodesForPlan(PlanNames.RETAIL_PRO))
        .thenReturn(List.of("A", "B", "C", "D", "E", "F", "G", "H"));
    when(usage.countModulesUsedSince(eq(accountId), any())).thenReturn(modulesUsed);
    when(invoices.listOpenStatuses(accountId))
        .thenReturn(
            billing <= 0
                ? List.of(InvoiceStatus.OVERDUE)
                : billing <= 70 ? List.of(InvoiceStatus.DUE) : List.of());
    when(support.scoreForAccount(accountId)).thenReturn(supportScore);
    when(business.scoreForAccount(accountId, pharmacyId)).thenReturn(businessScore);
    when(health.findByAccountId(accountId)).thenReturn(Optional.empty());
    when(usage.pharmacyName(pharmacyId)).thenReturn("X");
    Map<String, Object> data = service.getHealth(ops, accountId);
    assertThat(data.get("health_band")).isEqualTo(band);
  }

  private void stubCompute(
      int modulesUsed,
      int modulesEligible,
      List<String> openStatuses,
      double supportScore,
      double businessScore) {
    when(plans.findAccountById(accountId)).thenReturn(Optional.of(account));
    stubRecompute(modulesUsed, modulesEligible, openStatuses, supportScore, businessScore);
  }

  private void stubRecompute(
      int modulesUsed,
      int modulesEligible,
      List<String> openStatuses,
      double supportScore,
      double businessScore) {
    when(plans.findPlanByName(PlanNames.RETAIL_PRO)).thenReturn(Optional.of(retailPro));
    List<String> codes = new java.util.ArrayList<>();
    for (int i = 0; i < modulesEligible; i++) {
      codes.add("M" + i);
    }
    when(plans.moduleCodesForPlan(PlanNames.RETAIL_PRO)).thenReturn(codes);
    when(usage.countModulesUsedSince(eq(accountId), any())).thenReturn(modulesUsed);
    when(invoices.listOpenStatuses(accountId)).thenReturn(openStatuses);
    when(support.scoreForAccount(accountId)).thenReturn(supportScore);
    when(business.scoreForAccount(accountId, pharmacyId)).thenReturn(businessScore);
  }

  private static boolean ChronoWithin24h(Instant a, Instant b) {
    return Math.abs(a.toEpochMilli() - b.toEpochMilli()) <= 24L * 3600_000L;
  }
}

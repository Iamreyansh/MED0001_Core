package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.CrmSubscriptionOutboxPort;
import com.nammamedmate.crm.application.port.out.InvoiceIssuingPort;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.application.port.out.SaasRenewalChurnStore;
import com.nammamedmate.crm.application.port.out.SaasSubscriptionStore;
import com.nammamedmate.crm.application.port.out.SubscriptionPaymentPort;
import com.nammamedmate.crm.domain.BillingCycle;
import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.crm.domain.SaasPlan;
import com.nammamedmate.crm.domain.SaasSubscription;
import com.nammamedmate.crm.domain.SubscriptionStatus;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RenewalChurnServiceGapsTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  @Mock SaasRenewalChurnStore store;
  @Mock SaasPlanStore plans;
  @Mock SaasSubscriptionStore subs;
  @Mock SubscriptionPaymentPort payments;
  @Mock InvoiceIssuingPort invoices;
  @Mock CrmSubscriptionOutboxPort outbox;

  RenewalChurnService service;
  MedmatePrincipal ops;
  MedmatePrincipal staff;
  UUID accountId;
  CrmAccount account;
  SaasPlan plan;

  @BeforeEach
  void setUp() {
    service =
        new RenewalChurnService(
            store, plans, subs, payments, invoices, outbox, Clock.fixed(NOW, ZoneOffset.UTC));
    ops = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "o");
    staff =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_STAFF, Ids.newId(), TokenScope.FULL, "s");
    accountId = Ids.newId();
    account = new CrmAccount(accountId, Ids.newId(), PlanNames.STARTER, "ACTIVE", NOW);
    plan = new SaasPlan(Ids.newId(), PlanNames.STARTER, 69900, 2, null, true, false, NOW);
  }

  @Test
  void authAndValidationBranches() {
    assertThatThrownBy(() -> service.dashboard(null)).extracting("code").isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.dashboard(staff)).extracting("code").isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.manualRenew(staff, accountId, false, null, "k"))
        .extracting("code")
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.manualRenew(null, accountId, false, null, "k"))
        .extracting("code")
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.manualRenew(ops, accountId, false, null, null))
        .extracting("code")
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.manualRenew(ops, accountId, false, null, "   "))
        .extracting("code")
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.manualRenew(ops, accountId, false, null, "x".repeat(129)))
        .extracting("code")
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.listUpcoming(ops, 0, null, null, 1, 20))
        .extracting("code")
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.listUpcoming(ops, 91, null, null, 1, 20))
        .extracting("code")
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.churnAnalysis(ops, "last_year"))
        .extracting("code")
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.logChurnSurvey(ops, accountId, "NOPE", null))
        .extracting("code")
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void waiveFeeAndExpiredSubscription() {
    when(plans.findAccountById(accountId)).thenReturn(Optional.of(account));
    SaasSubscription expired =
        new SaasSubscription(
            Ids.newId(),
            accountId,
            plan.id(),
            null,
            SubscriptionStatus.EXPIRED,
            BillingCycle.MONTHLY,
            NOW,
            null,
            false,
            null,
            null,
            NOW,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    when(subs.findByAccountId(accountId)).thenReturn(Optional.of(expired));
    assertThatThrownBy(() -> service.manualRenew(ops, accountId, true, "x", "k"))
        .extracting("code")
        .isEqualTo("SUBSCRIPTION_NOT_ACTIVE");

    SaasSubscription due =
        new SaasSubscription(
            Ids.newId(),
            accountId,
            plan.id(),
            null,
            SubscriptionStatus.ACTIVE,
            BillingCycle.MONTHLY,
            Instant.parse("2026-07-26T00:00:00Z"),
            null,
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    when(subs.findByAccountId(accountId)).thenReturn(Optional.of(due));
    when(plans.findPlanById(plan.id())).thenReturn(Optional.of(plan));
    when(plans.listActiveAddons()).thenReturn(List.of());
    when(invoices.issue(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Ids.newId());

    Map<String, Object> waived = service.manualRenew(ops, accountId, true, "goodwill", "k");
    assertThat(waived)
        .containsEntry("waive_fee", true)
        .containsEntry("amount_charged_rs", java.math.BigDecimal.ZERO.setScale(2));
    Map<String, Object> waivedNoReason =
        service.manualRenew(ops, accountId, true, null, "k-waive2");
    assertThat(waivedNoReason).containsEntry("waive_fee", true);
  }

  @Test
  void freePlanAndMissingSub() {
    when(plans.findAccountById(accountId)).thenReturn(Optional.of(account));
    when(subs.findByAccountId(accountId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.manualRenew(ops, accountId, false, null, "k"))
        .extracting("code")
        .isEqualTo("SUBSCRIPTION_NOT_FOUND");

    SaasPlan free = new SaasPlan(Ids.newId(), PlanNames.FREE, 0, 1, null, true, false, NOW);
    SaasSubscription sub =
        new SaasSubscription(
            Ids.newId(),
            accountId,
            free.id(),
            null,
            SubscriptionStatus.ACTIVE,
            BillingCycle.MONTHLY,
            Instant.parse("2026-07-26T00:00:00Z"),
            null,
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    when(subs.findByAccountId(accountId)).thenReturn(Optional.of(sub));
    when(plans.findPlanById(free.id())).thenReturn(Optional.of(free));
    assertThatThrownBy(() -> service.manualRenew(ops, accountId, false, null, "k"))
        .extracting("code")
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void atRiskAlertsAndPeriods() {
    UUID subId = Ids.newId();
    when(store.findAtRiskRenewals(any(), any()))
        .thenReturn(List.of(new SaasRenewalChurnStore.AtRiskAlertRow(accountId, subId, 40.0)));
    service.processAtRiskCsmAlerts();
    verify(outbox).publish(eq(RenewalChurnService.AT_RISK_CSM_EVENT), eq(subId), any());

    when(store.churnReasons(any(), any())).thenReturn(List.of());
    when(store.cohortChurnRates(any()))
        .thenReturn(
            List.of(
                new SaasRenewalChurnStore.CohortRate(
                    "2026-01", new BigDecimal("2.1"), new BigDecimal("5.8"), null)));
    when(store.countChurnedLogos(any(), any())).thenReturn(0L);
    when(store.countChurnedWithLowAdoption(any(), any())).thenReturn(0L);
    when(store.countChurnedWithMissedPayments(any(), any())).thenReturn(0L);

    Map<String, Object> p30 = service.churnAnalysis(ops, "last_30d");
    assertThat(p30).containsEntry("period", "last_30d");
    Map<String, Object> p6 = service.churnAnalysis(ops, "last_6m");
    assertThat(p6).containsEntry("period", "last_6m");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> cohorts = (List<Map<String, Object>>) p6.get("cohort_churn_rates");
    assertThat(cohorts.getFirst()).containsEntry("month_6_churn_pct", null);
  }

  @Test
  void dashboardIncludesChurnLogAndCsmFilter() {
    when(store.countRenewing(any(), any())).thenReturn(1L);
    when(store.sumMrrAtRiskPaise(any(), any())).thenReturn(0L);
    when(store.countChurnedLogos(any(), any())).thenReturn(1L);
    when(store.countStartOfPeriodLogos(any(), any())).thenReturn(10L);
    when(store.sumMrrChurnedPaise(any(), any())).thenReturn(69900L);
    when(store.countSavePlaysSince(any())).thenReturn(0L);
    when(store.churnReasons(any(), any()))
        .thenReturn(List.of(new SaasRenewalChurnStore.ReasonCount("NOT_USING", 1)));
    when(store.listUpcoming(any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new SaasRenewalChurnStore.UpcomingRow(
                    accountId,
                    "City",
                    PlanNames.STARTER,
                    69900,
                    LocalDate.of(2026, 8, 1),
                    false,
                    80,
                    Instant.parse("2026-07-20T00:00:00Z"),
                    "Sneha")));
    when(store.churnLog(any(), any(), eq(50)))
        .thenReturn(
            List.of(
                new SaasRenewalChurnStore.ChurnLogRow(
                    accountId,
                    "City",
                    PlanNames.STARTER,
                    69900,
                    Instant.parse("2026-07-18T00:00:00Z"),
                    "NOT_USING")));

    Map<String, Object> dash = service.dashboard(ops);
    assertThat(dash).containsKeys("churn_log", "upcoming_renewals", "churn_reasons_chart");

    when(store.countUpcoming(any(), any(), any(), any())).thenReturn(0L);
    when(store.listUpcoming(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
    assertThat(service.listUpcoming(ops, 30, "LOW", Ids.newId(), 1, 10).meta().total()).isZero();
  }

  @Test
  void surveyNotesBlankAndFinanceCannotWrite() {
    MedmatePrincipal finance =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "f");
    assertThatThrownBy(() -> service.logChurnSurvey(finance, accountId, "PRICE", "x"))
        .isInstanceOf(AppException.class)
        .extracting("code")
        .isEqualTo("FORBIDDEN");

    when(plans.findAccountById(accountId)).thenReturn(Optional.of(account));
    when(store.insertSurvey(any())).thenAnswer(inv -> inv.getArgument(0));
    Map<String, Object> data = service.logChurnSurvey(ops, accountId, "OTHER", "   ");
    assertThat(data).containsEntry("reason", "OTHER");
  }
}

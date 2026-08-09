package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.CrmSubscriptionOutboxPort;
import com.nammamedmate.crm.application.port.out.InvoiceIssuingPort;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.application.port.out.SaasRenewalChurnStore;
import com.nammamedmate.crm.application.port.out.SaasSubscriptionStore;
import com.nammamedmate.crm.application.port.out.SubscriptionPaymentPort;
import com.nammamedmate.crm.domain.BillingCycle;
import com.nammamedmate.crm.domain.ChurnMath;
import com.nammamedmate.crm.domain.ChurnSurvey;
import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.crm.domain.RenewalRiskLevel;
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
import java.util.ArrayList;
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
class RenewalChurnServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  @Mock SaasRenewalChurnStore store;
  @Mock SaasPlanStore plans;
  @Mock SaasSubscriptionStore subs;
  @Mock SubscriptionPaymentPort payments;
  @Mock InvoiceIssuingPort invoices;
  @Mock CrmSubscriptionOutboxPort outbox;

  RenewalChurnService service;
  MedmatePrincipal ops;
  MedmatePrincipal finance;
  UUID accountId;
  UUID pharmacyId;
  CrmAccount account;
  SaasPlan starter;
  List<Map<String, Object>> events;

  @BeforeEach
  void setUp() {
    events = new ArrayList<>();
    service =
        new RenewalChurnService(
            store,
            plans,
            subs,
            payments,
            invoices,
            (type, id, payload) -> events.add(Map.of("type", type, "id", id, "payload", payload)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    ops = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "o");
    finance = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "f");
    accountId = Ids.newId();
    pharmacyId = Ids.newId();
    account = new CrmAccount(accountId, pharmacyId, PlanNames.STARTER, "ACTIVE", NOW);
    starter = new SaasPlan(Ids.newId(), PlanNames.STARTER, 69900, 2, null, true, false, NOW);
    lenient().when(plans.listActiveAddons()).thenReturn(List.of());
    lenient()
        .when(
            invoices.issue(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(inv -> Ids.newId());
  }

  @Test
  @DisplayName("AC-001 renewal pipeline shows next 30d with days_until_renewal")
  void ac001_upcomingDays() {
    LocalDate renewal = LocalDate.of(2026, 8, 5);
    when(store.listUpcoming(any(), any(), isNull(), isNull(), eq(0), eq(20)))
        .thenReturn(
            List.of(
                new SaasRenewalChurnStore.UpcomingRow(
                    accountId,
                    "Apollo Pharmacy HSR",
                    PlanNames.RETAIL_PRO,
                    149900,
                    renewal,
                    true,
                    42,
                    null,
                    null)));
    when(store.countUpcoming(any(), any(), isNull(), isNull())).thenReturn(1L);

    RenewalChurnService.PagedResult result = service.listUpcoming(finance, 30, null, null, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows =
        (List<Map<String, Object>>) result.data().get("upcoming_renewals");
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst()).containsEntry("days_until_renewal", 12L);
    assertThat(rows.getFirst()).containsEntry("renewal_date", "2026-08-05");
  }

  @Test
  @DisplayName("AC-002 health_score < 50 → risk_level HIGH")
  void ac002_highRisk() {
    when(store.listUpcoming(any(), any(), eq(RenewalRiskLevel.HIGH), isNull(), eq(0), eq(20)))
        .thenReturn(
            List.of(
                new SaasRenewalChurnStore.UpcomingRow(
                    accountId,
                    "Shop",
                    PlanNames.STARTER,
                    69900,
                    LocalDate.of(2026, 8, 1),
                    true,
                    42,
                    null,
                    null)));
    when(store.countUpcoming(any(), any(), eq(RenewalRiskLevel.HIGH), isNull())).thenReturn(1L);

    RenewalChurnService.PagedResult result =
        service.listUpcoming(ops, null, "HIGH", null, null, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows =
        (List<Map<String, Object>>) result.data().get("upcoming_renewals");
    assertThat(rows.getFirst()).containsEntry("risk_level", RenewalRiskLevel.HIGH);
    assertThat(rows.getFirst()).containsEntry("health_score", 42.0);
  }

  @Test
  @DisplayName("AC-003 logo_churn_pct = churned/start × 100")
  void ac003_logoChurn() {
    stubDashboard(8, 606, 12);
    Map<String, Object> data = service.dashboard(finance);
    @SuppressWarnings("unchecked")
    Map<String, Object> chips = (Map<String, Object>) data.get("chips");
    assertThat(chips.get("logo_churn_pct")).isEqualTo(ChurnMath.logoChurnPct(8, 606));
    assertThat(chips.get("churned_logos_this_month")).isEqualTo(8L);
  }

  @Test
  @DisplayName("AC-004 manual renew generates invoice and advances renewal")
  void ac004_manualRenew() {
    Instant renewal = Instant.parse("2026-07-28T00:00:00Z");
    SaasSubscription sub = sub(renewal);
    when(plans.findAccountById(accountId)).thenReturn(Optional.of(account));
    when(subs.findByAccountId(accountId)).thenReturn(Optional.of(sub));
    when(plans.findPlanById(sub.planId())).thenReturn(Optional.of(starter));
    UUID invoiceId = Ids.newId();
    when(invoices.issue(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(invoiceId);

    Map<String, Object> data = service.manualRenew(ops, accountId, false, "early", "k");
    assertThat(data).containsEntry("invoice_id", invoiceId);
    assertThat(data.get("new_renewal_date")).isEqualTo("2026-08-28");
    verify(payments).charge(eq(accountId), eq(69900L), any(), any());
    ArgumentCaptor<SaasSubscription> cap = ArgumentCaptor.forClass(SaasSubscription.class);
    verify(subs).update(cap.capture());
    assertThat(cap.getValue().renewalDate())
        .isEqualTo(BillingCycle.advance(renewal, BillingCycle.MONTHLY));
  }

  @Test
  @DisplayName("AC-005 churn survey logs reason and appears in analysis counts")
  void ac005_survey() {
    when(plans.findAccountById(accountId)).thenReturn(Optional.of(account));
    when(store.insertSurvey(any()))
        .thenAnswer(
            inv -> {
              ChurnSurvey s = inv.getArgument(0);
              return s;
            });
    when(store.churnReasons(any(), any()))
        .thenReturn(List.of(new SaasRenewalChurnStore.ReasonCount("PRICE", 1)));
    when(store.cohortChurnRates(any())).thenReturn(List.of());
    when(store.countChurnedLogos(any(), any())).thenReturn(1L);
    when(store.countChurnedWithLowAdoption(any(), any())).thenReturn(0L);
    when(store.countChurnedWithMissedPayments(any(), any())).thenReturn(0L);

    Map<String, Object> logged = service.logChurnSurvey(ops, accountId, "PRICE", "notes");
    assertThat(logged).containsEntry("reason", "PRICE");
    Map<String, Object> analysis = service.churnAnalysis(finance, "last_30d");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> chart =
        (List<Map<String, Object>>) analysis.get("churn_reasons_chart");
    assertThat(chart.getFirst()).containsEntry("reason", "PRICE").containsEntry("count", 1L);
  }

  @Test
  @DisplayName("AC-006 churn reasons chart pct sums to 100")
  void ac006_pctSum() {
    when(store.churnReasons(any(), any()))
        .thenReturn(
            List.of(
                new SaasRenewalChurnStore.ReasonCount("PRICE", 12),
                new SaasRenewalChurnStore.ReasonCount("FEATURES", 8),
                new SaasRenewalChurnStore.ReasonCount("MOVING_TO_COMPETITOR", 6),
                new SaasRenewalChurnStore.ReasonCount("NOT_USING", 4),
                new SaasRenewalChurnStore.ReasonCount("CLOSING_BUSINESS", 1),
                new SaasRenewalChurnStore.ReasonCount("OTHER", 1)));
    when(store.cohortChurnRates(any())).thenReturn(List.of());
    when(store.countChurnedLogos(any(), any())).thenReturn(32L);
    when(store.countChurnedWithLowAdoption(any(), any())).thenReturn(22L);
    when(store.countChurnedWithMissedPayments(any(), any())).thenReturn(14L);

    Map<String, Object> data = service.churnAnalysis(ops, "last_90d");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> chart = (List<Map<String, Object>>) data.get("churn_reasons_chart");
    BigDecimal sum =
        chart.stream().map(r -> (BigDecimal) r.get("pct")).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo(new BigDecimal("100.000"));
  }

  @Test
  @DisplayName("AC-007 at_risk_indicators correlate churn with leading signals")
  void ac007_indicators() {
    when(store.churnReasons(any(), any())).thenReturn(List.of());
    when(store.cohortChurnRates(any())).thenReturn(List.of());
    when(store.countChurnedLogos(any(), any())).thenReturn(32L);
    when(store.countChurnedWithLowAdoption(any(), any())).thenReturn(22L);
    when(store.countChurnedWithMissedPayments(any(), any())).thenReturn(14L);

    Map<String, Object> data = service.churnAnalysis(finance, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> ind = (List<Map<String, Object>>) data.get("at_risk_indicators");
    assertThat(ind).hasSize(2);
    assertThat(ind.getFirst()).containsEntry("churned_with_this", 22L);
    assertThat(ind.get(1)).containsEntry("pct_of_churned", ChurnMath.pctOf(14, 32));
  }

  @Test
  @DisplayName("AC-008 win-back outbox 7 days after EXPIRED")
  void ac008_winback() {
    when(store.findWinbackDue(any(), any())).thenReturn(List.of(accountId));
    service.processWinbacks();
    assertThat(events).anyMatch(e -> RenewalChurnService.WINBACK_EVENT.equals(e.get("type")));
  }

  @Test
  @DisplayName("AC-009 save play banner counts plays in past 7 days")
  void ac009_savePlayBanner() {
    stubDashboard(0, 10, 12);
    Map<String, Object> data = service.dashboard(ops);
    @SuppressWarnings("unchecked")
    Map<String, Object> banner = (Map<String, Object>) data.get("save_play_banner");
    assertThat(banner).containsEntry("active_save_plays", 12L);
    assertThat(banner.get("message").toString()).contains("12 active save plays");
  }

  @Test
  @DisplayName("AC-010 monthly churn report on 1st emits outbox")
  void ac010_monthlyReport() {
    RenewalChurnService firstOfMonth =
        new RenewalChurnService(
            store,
            plans,
            subs,
            payments,
            invoices,
            (type, id, payload) -> events.add(Map.of("type", type, "id", id, "payload", payload)),
            Clock.fixed(Instant.parse("2026-08-01T02:00:00Z"), ZoneOffset.UTC));
    when(store.countChurnedLogos(any(), any())).thenReturn(8L);
    when(store.countStartOfPeriodLogos(any(), any())).thenReturn(606L);
    when(store.sumMrrChurnedPaise(any(), any())).thenReturn(1019400L);

    firstOfMonth.processMonthlyChurnReport();
    assertThat(events)
        .anyMatch(e -> RenewalChurnService.MONTHLY_REPORT_EVENT.equals(e.get("type")));

    events.clear();
    service.processMonthlyChurnReport(); // July 24 — no-op
    assertThat(events).isEmpty();
  }

  @Test
  void subscriptionNotDueAndAccountNotFound() {
    when(plans.findAccountById(accountId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.manualRenew(ops, accountId, false, null, "k"))
        .isInstanceOf(AppException.class)
        .extracting("code")
        .isEqualTo("ACCOUNT_NOT_FOUND");

    when(plans.findAccountById(accountId)).thenReturn(Optional.of(account));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(Instant.parse("2026-09-01T00:00:00Z"))));
    assertThatThrownBy(() -> service.manualRenew(ops, accountId, true, "x", "k"))
        .extracting("code")
        .isEqualTo("SUBSCRIPTION_NOT_DUE");
  }

  private void stubDashboard(long churned, long start, long savePlays) {
    when(store.countRenewing(any(), any())).thenReturn(42L);
    when(store.sumMrrAtRiskPaise(any(), any())).thenReturn(5838000L);
    when(store.countChurnedLogos(any(), any())).thenReturn(churned);
    when(store.countStartOfPeriodLogos(any(), any())).thenReturn(start);
    when(store.sumMrrChurnedPaise(any(), any())).thenReturn(1019400L);
    when(store.countSavePlaysSince(any())).thenReturn(savePlays);
    when(store.churnReasons(any(), any())).thenReturn(List.of());
    when(store.listUpcoming(any(), any(), isNull(), isNull(), eq(0), eq(50))).thenReturn(List.of());
    when(store.churnLog(any(), any(), eq(50))).thenReturn(List.of());
  }

  private SaasSubscription sub(Instant renewal) {
    return new SaasSubscription(
        Ids.newId(),
        accountId,
        starter.id(),
        null,
        SubscriptionStatus.ACTIVE,
        BillingCycle.MONTHLY,
        renewal,
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
  }
}

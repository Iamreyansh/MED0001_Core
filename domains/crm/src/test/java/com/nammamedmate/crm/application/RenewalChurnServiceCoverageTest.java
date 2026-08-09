package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.CrmSubscriptionOutboxPort;
import com.nammamedmate.crm.application.port.out.InvoiceIssuingPort;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.application.port.out.SaasRenewalChurnStore;
import com.nammamedmate.crm.application.port.out.SaasSubscriptionStore;
import com.nammamedmate.crm.application.port.out.SubscriptionPaymentPort;
import com.nammamedmate.crm.domain.AccountAddon;
import com.nammamedmate.crm.domain.BillingCycle;
import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.crm.domain.SaasAddon;
import com.nammamedmate.crm.domain.SaasPlan;
import com.nammamedmate.crm.domain.SaasSubscription;
import com.nammamedmate.crm.domain.SubscriptionStatus;
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
class RenewalChurnServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  @Mock SaasRenewalChurnStore store;
  @Mock SaasPlanStore plans;
  @Mock SaasSubscriptionStore subs;
  @Mock SubscriptionPaymentPort payments;
  @Mock InvoiceIssuingPort invoices;
  @Mock CrmSubscriptionOutboxPort outbox;

  RenewalChurnService service;
  MedmatePrincipal superAdmin;
  UUID accountId;
  CrmAccount account;
  SaasPlan plan;

  @BeforeEach
  void setUp() {
    service =
        new RenewalChurnService(
            store, plans, subs, payments, invoices, outbox, Clock.fixed(NOW, ZoneOffset.UTC));
    superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "s");
    accountId = Ids.newId();
    account = new CrmAccount(accountId, Ids.newId(), PlanNames.STARTER, "ACTIVE", NOW);
    plan = new SaasPlan(Ids.newId(), PlanNames.STARTER, 69900, 2, null, true, false, NOW);
  }

  @Test
  void coversPaginationAuthAddonsAndNullEdges() {
    assertThat(new RenewalChurnService.PagedResult(null, null).data()).isEmpty();

    when(store.listUpcoming(any(), any(), any(), any(), eq(0), eq(100))).thenReturn(List.of());
    when(store.listUpcoming(any(), any(), any(), any(), eq(0), eq(20))).thenReturn(List.of());
    when(store.countUpcoming(any(), any(), any(), any())).thenReturn(0L);
    service.listUpcoming(superAdmin, 10, "MEDIUM", null, 0, 500);
    service.listUpcoming(superAdmin, 10, null, null, 1, 0);

    when(store.listUpcoming(any(), any(), any(), any(), eq(0), eq(20)))
        .thenReturn(
            List.of(
                new SaasRenewalChurnStore.UpcomingRow(
                    accountId, "P", PlanNames.STARTER, 69900, null, true, 75, null, null)));
    when(store.countUpcoming(any(), any(), any(), any())).thenReturn(1L);
    RenewalChurnService.PagedResult page =
        service.listUpcoming(superAdmin, null, null, null, null, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows =
        (List<Map<String, Object>>) page.data().get("upcoming_renewals");
    assertThat(rows.getFirst()).containsEntry("days_until_renewal", 0L);
    assertThat(rows.getFirst()).containsEntry("renewal_date", null);

    when(plans.findAccountById(accountId)).thenReturn(Optional.of(account));
    when(store.insertSurvey(any())).thenAnswer(inv -> inv.getArgument(0));
    assertThat(service.logChurnSurvey(superAdmin, accountId, "FEATURES", "kept"))
        .containsEntry("reason", "FEATURES");
    assertThat(service.logChurnSurvey(superAdmin, accountId, "OTHER", null))
        .containsEntry("reason", "OTHER");

    SaasAddon addon = new SaasAddon(Ids.newId(), "SMS_PACK", 9900, "sms", true);
    SaasAddon unused = new SaasAddon(Ids.newId(), "OTHER_ADDON", 100, "x", true);
    SaasSubscription sub =
        new SaasSubscription(
            Ids.newId(),
            accountId,
            plan.id(),
            null,
            SubscriptionStatus.ACTIVE,
            BillingCycle.ANNUAL,
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
    when(plans.findPlanById(plan.id())).thenReturn(Optional.of(plan));
    when(plans.listActiveAddons()).thenReturn(List.of(addon, unused));
    when(plans.findActiveAccountAddon(accountId, addon.id()))
        .thenReturn(Optional.of(new AccountAddon(accountId, addon.id(), NOW, null)));
    when(plans.findActiveAccountAddon(accountId, unused.id())).thenReturn(Optional.empty());
    when(invoices.issue(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Ids.newId());

    Map<String, Object> renewed = service.manualRenew(superAdmin, accountId, false, null, "k");
    assertThat(renewed).containsEntry("waive_fee", false);
    verify(payments).charge(eq(accountId), any(Long.class), any(), any());

    when(plans.findPlanById(plan.id())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.manualRenew(superAdmin, accountId, false, "x", "k"))
        .extracting("code")
        .isEqualTo("PLAN_NOT_FOUND");

    SaasSubscription cancelled =
        new SaasSubscription(
            Ids.newId(),
            accountId,
            plan.id(),
            null,
            SubscriptionStatus.CANCELLED,
            BillingCycle.MONTHLY,
            NOW,
            null,
            false,
            NOW,
            NOW,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    when(subs.findByAccountId(accountId)).thenReturn(Optional.of(cancelled));
    assertThatThrownBy(() -> service.manualRenew(superAdmin, accountId, false, null, "k"))
        .extracting("code")
        .isEqualTo("SUBSCRIPTION_NOT_ACTIVE");

    when(store.churnReasons(any(), any())).thenReturn(List.of());
    when(store.cohortChurnRates(any())).thenReturn(List.of());
    when(store.countChurnedLogos(any(), any())).thenReturn(0L);
    when(store.countChurnedWithLowAdoption(any(), any())).thenReturn(0L);
    when(store.countChurnedWithMissedPayments(any(), any())).thenReturn(0L);
    assertThat(service.churnAnalysis(superAdmin, "  ")).containsEntry("period", "last_90d");
  }
}

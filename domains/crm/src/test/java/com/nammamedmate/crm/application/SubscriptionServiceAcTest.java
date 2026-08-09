package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.crm.application.port.out.CrmAuditPort;
import com.nammamedmate.crm.application.port.out.CrmSubscriptionOutboxPort;
import com.nammamedmate.crm.application.port.out.InvoiceIssuingPort;
import com.nammamedmate.crm.application.port.out.PharmacyPlanSyncPort;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.application.port.out.SaasRenewalChurnStore;
import com.nammamedmate.crm.application.port.out.SaasSubscriptionIdempotencyStore;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
class SubscriptionServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID FREE_ID = UUID.fromString("a1000000-0000-4000-8000-000000000001");
  private static final UUID STARTER_ID = UUID.fromString("a1000000-0000-4000-8000-000000000002");
  private static final UUID RETAIL_ID = UUID.fromString("a1000000-0000-4000-8000-000000000003");

  @Mock SaasPlanStore plans;
  @Mock SaasSubscriptionStore subs;
  @Mock SubscriptionPaymentPort payments;
  @Mock InvoiceIssuingPort invoices;
  @Mock PharmacyPlanSyncPort planSync;
  @Mock CrmSubscriptionOutboxPort outbox;
  @Mock CrmAuditPort audit;
  @Mock SaasRenewalChurnStore cohorts;
  @Mock SaasSubscriptionIdempotencyStore idempotency;

  SubscriptionService service;
  UUID pharmacyId;
  UUID accountId;
  UUID subId;
  MedmatePrincipal owner;
  MedmatePrincipal admin;
  List<Map<String, Object>> outboxEvents;

  @BeforeEach
  void setUp() {
    outboxEvents = new ArrayList<>();
    service =
        new SubscriptionService(
            plans,
            subs,
            payments,
            invoices,
            planSync,
            (type, id, payload) ->
                outboxEvents.add(Map.of("type", type, "id", id, "payload", payload)),
            audit,
            cohorts,
            idempotency,
            new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    pharmacyId = Ids.newId();
    accountId = Ids.newId();
    subId = Ids.newId();
    owner =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
    admin = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "a");
    lenient().when(plans.listActiveAddons()).thenReturn(List.of());
    lenient().when(idempotency.findByKey(anyString())).thenReturn(Optional.empty());
    lenient()
        .when(
            invoices.issue(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(inv -> Ids.newId());
  }

  @Test
  @DisplayName("AC-001 New pharmacy registration creates FREE ACTIVE subscription")
  void ac001_ensureFree() {
    when(plans.findAccountByPharmacyId(pharmacyId)).thenReturn(Optional.empty());
    when(plans.createAccount(
            eq(pharmacyId), eq(PlanNames.FREE), eq(SubscriptionStatus.ACTIVE), any()))
        .thenReturn(
            new CrmAccount(accountId, pharmacyId, PlanNames.FREE, SubscriptionStatus.ACTIVE, NOW));
    when(subs.findByAccountId(accountId)).thenReturn(Optional.empty());
    when(plans.findPlanByName(PlanNames.FREE))
        .thenReturn(Optional.of(plan(FREE_ID, PlanNames.FREE, 0)));

    service.ensureFreeSubscription(pharmacyId);

    ArgumentCaptor<SaasSubscription> cap = ArgumentCaptor.forClass(SaasSubscription.class);
    verify(subs).insert(cap.capture());
    assertThat(cap.getValue().status()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(cap.getValue().planId()).isEqualTo(FREE_ID);
    verify(planSync).syncPlan(pharmacyId, PlanNames.FREE);
  }

  @Test
  @DisplayName("AC-002 Subscribe STARTER MONTHLY charges Rs 699 and becomes ACTIVE")
  void ac002_subscribeStarter() {
    stubAccountAndFreeSub();
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    UUID invoice = Ids.newId();
    when(payments.charge(eq(accountId), eq(69900L), anyString(), anyString()))
        .thenReturn(Ids.newId());
    when(invoices.issue(
            eq(accountId),
            any(),
            eq(PlanNames.STARTER),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(invoice);

    Map<String, Object> data =
        service.subscribe(owner, STARTER_ID, BillingCycle.MONTHLY, null, "k");

    assertThat(data)
        .containsEntry("plan", PlanNames.STARTER)
        .containsEntry("status", SubscriptionStatus.ACTIVE)
        .containsEntry("invoice_id", invoice);
    assertThat(data.get("amount_charged_rs").toString()).isEqualTo("699.00");
    verify(subs).update(any());
  }

  @Test
  @DisplayName("AC-003 Mid-cycle upgrade STARTER→RETAIL_PRO produces prorated charge")
  void ac003_upgradeProration() {
    Instant renewal = NOW.plus(15, ChronoUnit.DAYS);
    SaasSubscription current = sub(STARTER_ID, SubscriptionStatus.ACTIVE, renewal, true);
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.STARTER, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId)).thenReturn(Optional.of(current));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    when(plans.findPlanById(RETAIL_ID))
        .thenReturn(Optional.of(plan(RETAIL_ID, PlanNames.RETAIL_PRO, 149900)));
    when(payments.charge(eq(accountId), anyLong(), anyString(), anyString()))
        .thenReturn(Ids.newId());

    Map<String, Object> data = service.upgrade(owner, RETAIL_ID, "k");

    assertThat(data)
        .containsEntry("previous_plan", PlanNames.STARTER)
        .containsEntry("new_plan", PlanNames.RETAIL_PRO)
        .containsEntry("effective_immediately", true);
    assertThat(((Number) data.get("prorated_credit_rs")).doubleValue()).isGreaterThan(0);
    assertThat(((Number) data.get("amount_charged_rs")).doubleValue()).isGreaterThan(0);
  }

  @Test
  @DisplayName("AC-004 Downgrade schedules plan change at renewal_date")
  void ac004_downgradeSchedules() {
    Instant renewal = NOW.plus(20, ChronoUnit.DAYS);
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.RETAIL_PRO, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(RETAIL_ID, SubscriptionStatus.ACTIVE, renewal, true)));
    when(plans.findPlanById(RETAIL_ID))
        .thenReturn(Optional.of(plan(RETAIL_ID, PlanNames.RETAIL_PRO, 149900)));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));

    Map<String, Object> data = service.downgrade(owner, STARTER_ID);

    assertThat(data)
        .containsEntry("current_plan", PlanNames.RETAIL_PRO)
        .containsEntry("scheduled_plan", PlanNames.STARTER)
        .containsEntry("effective_date", renewal);
    ArgumentCaptor<SaasSubscription> cap = ArgumentCaptor.forClass(SaasSubscription.class);
    verify(subs).update(cap.capture());
    assertThat(cap.getValue().scheduledPlanId()).isEqualTo(STARTER_ID);
    assertThat(cap.getValue().planId()).isEqualTo(RETAIL_ID);
  }

  @Test
  @DisplayName("AC-005 Cancel sets cancels_at=renewal_date; stays ACTIVE")
  void ac005_cancelEop() {
    Instant renewal = NOW.plus(10, ChronoUnit.DAYS);
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.STARTER, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.ACTIVE, renewal, true)));

    Map<String, Object> data = service.cancel(owner);

    assertThat(data)
        .containsEntry("status", SubscriptionStatus.ACTIVE)
        .containsEntry("cancels_at", renewal);
    assertThat(outboxEvents).anyMatch(e -> "crm.subscription.churn_survey".equals(e.get("type")));
  }

  @Test
  @DisplayName("AC-006 Auto-renew payment failure → PAST_DUE; modules still accessible")
  void ac006_pastDueOnRenewFail() {
    Instant renewal = NOW.plus(2, ChronoUnit.DAYS);
    SaasSubscription current = sub(STARTER_ID, SubscriptionStatus.ACTIVE, renewal, true);
    when(subs.findDueForAutoRenew(any(), any())).thenReturn(List.of(current));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    when(payments.charge(any(), anyLong(), anyString(), anyString()))
        .thenThrow(new AppException("PAYMENT_FAILED", "fail", 402));
    when(subs.findPastDueExpired(any())).thenReturn(List.of());
    when(subs.findTrialsEnding(any())).thenReturn(List.of());
    when(subs.findCancelsDue(any())).thenReturn(List.of());
    when(subs.findOverridesExpired(any())).thenReturn(List.of());

    service.processScheduledJobs();

    ArgumentCaptor<SaasSubscription> cap = ArgumentCaptor.forClass(SaasSubscription.class);
    verify(subs).update(cap.capture());
    assertThat(cap.getValue().status()).isEqualTo(SubscriptionStatus.PAST_DUE);
    assertThat(outboxEvents)
        .anyMatch(e -> "crm.subscription.dunning_started".equals(e.get("type")));
    assertThat(SubscriptionStatus.hasModuleAccess(SubscriptionStatus.PAST_DUE)).isTrue();
  }

  @Test
  @DisplayName("AC-007 After 7 days PAST_DUE → EXPIRED; premium locked (effective FREE)")
  void ac007_expireAfterGrace() {
    SaasSubscription pastDue =
        new SaasSubscription(
            subId,
            accountId,
            STARTER_ID,
            null,
            SubscriptionStatus.PAST_DUE,
            BillingCycle.MONTHLY,
            NOW.minus(1, ChronoUnit.DAYS),
            null,
            true,
            null,
            null,
            null,
            NOW.minus(8, ChronoUnit.DAYS),
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    when(subs.findDueForAutoRenew(any(), any())).thenReturn(List.of());
    when(subs.findPastDueExpired(any())).thenReturn(List.of(pastDue));
    when(subs.findTrialsEnding(any())).thenReturn(List.of());
    when(subs.findCancelsDue(any())).thenReturn(List.of());
    when(subs.findOverridesExpired(any())).thenReturn(List.of());
    when(plans.findPlanByName(PlanNames.FREE))
        .thenReturn(Optional.of(plan(FREE_ID, PlanNames.FREE, 0)));

    service.processScheduledJobs();

    ArgumentCaptor<SaasSubscription> cap = ArgumentCaptor.forClass(SaasSubscription.class);
    verify(subs).update(cap.capture());
    assertThat(cap.getValue().status()).isEqualTo(SubscriptionStatus.EXPIRED);
    assertThat(service.effectivePlanName(pastDue, NOW)).isEqualTo(PlanNames.FREE);
  }

  @Test
  @DisplayName("AC-008 Admin override grants RETAIL_PRO ≤90d and audits")
  void ac008_adminOverride() {
    when(plans.findAccountById(accountId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.FREE, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(
            Optional.of(
                sub(FREE_ID, SubscriptionStatus.ACTIVE, NOW.plus(30, ChronoUnit.DAYS), true)));
    when(plans.findPlanById(RETAIL_ID))
        .thenReturn(Optional.of(plan(RETAIL_ID, PlanNames.RETAIL_PRO, 149900)));
    Instant expires = NOW.plus(90, ChronoUnit.DAYS);

    Map<String, Object> data =
        service.overrideSubscription(admin, accountId, RETAIL_ID, "Partner deal", expires);

    assertThat(data).containsEntry("override_plan", PlanNames.RETAIL_PRO);
    verify(audit)
        .append(
            eq("saas_subscription"),
            any(),
            any(),
            eq(subId),
            eq("saas_subscription.override"),
            any(),
            any());
  }

  @Test
  @DisplayName("AC-009 Toggle auto-renew false does not cancel immediately")
  void ac009_autoRenewToggle() {
    Instant renewal = NOW.plus(12, ChronoUnit.DAYS);
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.STARTER, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.ACTIVE, renewal, true)));

    Map<String, Object> data = service.setAutoRenew(owner, false);

    assertThat(data).containsEntry("auto_renew", false);
    ArgumentCaptor<SaasSubscription> cap = ArgumentCaptor.forClass(SaasSubscription.class);
    verify(subs).update(cap.capture());
    assertThat(cap.getValue().autoRenew()).isFalse();
    assertThat(cap.getValue().status()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(cap.getValue().cancelsAt()).isNull();
  }

  @Test
  @DisplayName("AC-010 Dunning outbox stub fired on PAST_DUE transition")
  void ac010_dunningOutbox() {
    SaasSubscription current =
        sub(STARTER_ID, SubscriptionStatus.ACTIVE, NOW.plus(1, ChronoUnit.DAYS), true);
    service.markPastDue(current, NOW);
    assertThat(outboxEvents)
        .anyMatch(e -> "crm.subscription.dunning_started".equals(e.get("type")));
  }

  @Test
  void overrideDurationExceeded() {
    assertThatThrownBy(
            () ->
                service.overrideSubscription(
                    admin, accountId, RETAIL_ID, "x", NOW.plus(91, ChronoUnit.DAYS)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("OVERRIDE_DURATION_EXCEEDED");
  }

  @Test
  void alreadySubscribed() {
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.STARTER, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(
            Optional.of(
                sub(STARTER_ID, SubscriptionStatus.ACTIVE, NOW.plus(10, ChronoUnit.DAYS), true)));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    assertThatThrownBy(() -> service.subscribe(owner, STARTER_ID, "MONTHLY", null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ALREADY_SUBSCRIBED");
  }

  @Test
  void invalidCoupon() {
    stubAccountAndFreeSub();
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    assertThatThrownBy(() -> service.subscribe(owner, STARTER_ID, "MONTHLY", "BAD", "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_COUPON");
  }

  @Test
  @DisplayName("Given Idempotency-Key When subscribe twice Then charge once and replay response")
  void subscribeIdempotentReplay() {
    stubAccountAndFreeSub();
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    when(payments.charge(eq(accountId), eq(69900L), anyString(), eq("idem-sub")))
        .thenReturn(Ids.newId());
    when(idempotency.findByKey("idem-sub"))
        .thenReturn(Optional.empty())
        .thenReturn(
            Optional.of(
                new SaasSubscriptionIdempotencyStore.CachedResponse(
                    "idem-sub",
                    accountId,
                    SaasSubscriptionIdempotencyStore.OP_SUBSCRIBE,
                    "{\"plan\":\"STARTER\",\"subscription_id\":\"" + subId + "\"}")));

    Map<String, Object> first = service.subscribe(owner, STARTER_ID, "MONTHLY", null, "idem-sub");
    Map<String, Object> second = service.subscribe(owner, STARTER_ID, "MONTHLY", null, "idem-sub");

    assertThat(first).containsEntry("plan", PlanNames.STARTER);
    assertThat(second).containsEntry("plan", PlanNames.STARTER);
    verify(payments).charge(eq(accountId), eq(69900L), anyString(), eq("idem-sub"));
    verify(idempotency)
        .insert(eq("idem-sub"), eq(accountId), eq("SUBSCRIBE"), anyString(), eq(NOW));
  }

  @Test
  @DisplayName("Given missing Idempotency-Key When subscribe Then VALIDATION_ERROR 400")
  void subscribeMissingIdempotencyKey() {
    assertThatThrownBy(() -> service.subscribe(owner, STARTER_ID, "MONTHLY", null, null))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("VALIDATION_ERROR");
              assertThat(ae.httpStatus()).isEqualTo(400);
              assertThat(ae.getMessage()).contains("Idempotency-Key is required");
            });
  }

  private void stubAccountAndFreeSub() {
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.FREE, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(
            Optional.of(
                sub(FREE_ID, SubscriptionStatus.ACTIVE, NOW.plus(30, ChronoUnit.DAYS), true)));
    when(plans.findPlanById(FREE_ID)).thenReturn(Optional.of(plan(FREE_ID, PlanNames.FREE, 0)));
  }

  private SaasSubscription sub(UUID planId, String status, Instant renewal, boolean autoRenew) {
    return new SaasSubscription(
        subId,
        accountId,
        planId,
        null,
        status,
        BillingCycle.MONTHLY,
        renewal,
        null,
        autoRenew,
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

  private static SaasPlan plan(UUID id, String name, long paise) {
    return new SaasPlan(id, name, paise, 2, 500, true, false, NOW);
  }
}

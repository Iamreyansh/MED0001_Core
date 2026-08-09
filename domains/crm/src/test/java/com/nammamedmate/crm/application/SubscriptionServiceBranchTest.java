package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.crm.application.port.out.CrmAuditPort;
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
class SubscriptionServiceBranchTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID FREE_ID = UUID.fromString("a1000000-0000-4000-8000-000000000001");
  private static final UUID STARTER_ID = UUID.fromString("a1000000-0000-4000-8000-000000000002");
  private static final UUID RETAIL_ID = UUID.fromString("a1000000-0000-4000-8000-000000000003");

  @Mock SaasPlanStore plans;
  @Mock SaasSubscriptionStore subs;
  @Mock SubscriptionPaymentPort payments;
  @Mock InvoiceIssuingPort invoices;
  @Mock PharmacyPlanSyncPort planSync;
  @Mock CrmAuditPort audit;
  @Mock SaasRenewalChurnStore cohorts;
  @Mock SaasSubscriptionIdempotencyStore idempotency;

  SubscriptionService service;
  UUID pharmacyId;
  UUID accountId;
  UUID subId;
  MedmatePrincipal owner;
  MedmatePrincipal admin;

  @BeforeEach
  void setUp() {
    service =
        new SubscriptionService(
            plans,
            subs,
            payments,
            invoices,
            planSync,
            (t, id, p) -> {},
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
  void ensureAlreadyPresentAndMissingFreePlan() {
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.FREE, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(FREE_ID, SubscriptionStatus.ACTIVE)));
    service.ensureFreeSubscription(pharmacyId);
    verify(subs, never()).insert(any());

    when(subs.findByAccountId(accountId)).thenReturn(Optional.empty());
    when(plans.findPlanByName(PlanNames.FREE)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.ensureFreeSubscription(pharmacyId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");
  }

  @Test
  void subscribeErrorBranches() {
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.FREE, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId)).thenReturn(Optional.empty());
    when(plans.findPlanByName(PlanNames.FREE))
        .thenReturn(Optional.of(plan(FREE_ID, PlanNames.FREE, 0)));
    assertThatThrownBy(() -> service.subscribe(owner, STARTER_ID, "MONTHLY", null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SUBSCRIPTION_NOT_FOUND");

    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.ACTIVE)));
    when(plans.findPlanById(STARTER_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.subscribe(owner, STARTER_ID, "MONTHLY", null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");

    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.TRIAL)));
    assertThatThrownBy(() -> service.subscribe(owner, RETAIL_ID, "MONTHLY", null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ALREADY_SUBSCRIBED");

    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(FREE_ID, SubscriptionStatus.ACTIVE)));
    when(plans.findPlanById(FREE_ID)).thenReturn(Optional.of(plan(FREE_ID, PlanNames.FREE, 0)));
    when(plans.findPlanById(STARTER_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.subscribe(owner, STARTER_ID, "MONTHLY", null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");

    when(plans.findPlanById(STARTER_ID)).thenReturn(Optional.of(plan(FREE_ID, PlanNames.FREE, 0)));
    assertThatThrownBy(() -> service.subscribe(owner, STARTER_ID, "MONTHLY", null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    when(payments.charge(any(), anyLong(), anyString(), anyString()))
        .thenThrow(new AppException("PAYMENT_FAILED", "x", 402));
    assertThatThrownBy(() -> service.subscribe(owner, STARTER_ID, "ANNUAL", "SAAS20", "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYMENT_FAILED");
  }

  @Test
  void upgradePlanNotFoundAndPaymentFail() {
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.STARTER, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.ACTIVE)));
    when(plans.findPlanById(STARTER_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.upgrade(owner, RETAIL_ID, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");

    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    when(plans.findPlanById(RETAIL_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.upgrade(owner, RETAIL_ID, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");

    when(plans.findPlanById(RETAIL_ID))
        .thenReturn(Optional.of(plan(RETAIL_ID, PlanNames.RETAIL_PRO, 149900)));
    when(payments.charge(any(), anyLong(), anyString(), anyString()))
        .thenThrow(new AppException("PAYMENT_FAILED", "x", 402));
    assertThatThrownBy(() -> service.upgrade(owner, RETAIL_ID, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PAYMENT_FAILED");
  }

  @Test
  void downgradePlanNotFound() {
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.RETAIL_PRO, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(RETAIL_ID, SubscriptionStatus.ACTIVE)));
    when(plans.findPlanById(RETAIL_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.downgrade(owner, STARTER_ID))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");
    when(plans.findPlanById(RETAIL_ID))
        .thenReturn(Optional.of(plan(RETAIL_ID, PlanNames.RETAIL_PRO, 149900)));
    when(plans.findPlanById(STARTER_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.downgrade(owner, STARTER_ID))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");
  }

  @Test
  void getCurrentPlanFallbackAndOwnerNullPharmacy() {
    MedmatePrincipal noPharm =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.getCurrent(noPharm))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.STARTER, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.ACTIVE)));
    when(plans.findPlanById(STARTER_ID)).thenReturn(Optional.empty());
    when(plans.findPlanByName(PlanNames.FREE)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getCurrent(owner))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");

    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    when(plans.findPlanByName(PlanNames.STARTER)).thenReturn(Optional.empty());
    when(plans.listActiveAddons()).thenReturn(List.of());
    when(plans.moduleCodesForPlan(PlanNames.STARTER)).thenReturn(List.of());
    assertThat(service.getCurrent(owner)).containsEntry("plan", PlanNames.STARTER);
  }

  @Test
  void overrideCreatesSubAndReactivatesExpired() {
    when(plans.findAccountById(accountId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.FREE, SubscriptionStatus.EXPIRED, NOW)));
    when(plans.findPlanById(RETAIL_ID))
        .thenReturn(Optional.of(plan(RETAIL_ID, PlanNames.RETAIL_PRO, 149900)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(sub(FREE_ID, SubscriptionStatus.EXPIRED)));
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.FREE, SubscriptionStatus.EXPIRED, NOW)));
    when(plans.findPlanByName(PlanNames.FREE))
        .thenReturn(Optional.of(plan(FREE_ID, PlanNames.FREE, 0)));
    Map<String, Object> data =
        service.overrideSubscription(
            admin, accountId, RETAIL_ID, "deal", NOW.plus(30, ChronoUnit.DAYS));
    assertThat(data).containsEntry("override_plan", PlanNames.RETAIL_PRO);
    verify(subs).insert(any());
  }

  @Test
  void autoRenewEnabledMessageAndOverrideGuards() {
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.STARTER, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.ACTIVE)));
    assertThat(service.setAutoRenew(owner, true).get("message").toString()).contains("enabled");

    assertThatThrownBy(
            () ->
                service.overrideSubscription(
                    owner, accountId, RETAIL_ID, "x", NOW.plus(10, ChronoUnit.DAYS)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.overrideSubscription(admin, accountId, RETAIL_ID, "x", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.overrideSubscription(
                    admin, accountId, RETAIL_ID, "x", NOW.minus(1, ChronoUnit.HOURS)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.overrideSubscription(
                    admin, accountId, RETAIL_ID, "  ", NOW.plus(10, ChronoUnit.DAYS)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(plans.findAccountById(accountId)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.overrideSubscription(
                    admin, accountId, RETAIL_ID, "deal", NOW.plus(10, ChronoUnit.DAYS)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ACCOUNT_NOT_FOUND");
    when(plans.findAccountById(accountId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.FREE, SubscriptionStatus.ACTIVE, NOW)));
    when(plans.findPlanById(RETAIL_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.overrideSubscription(
                    admin, accountId, RETAIL_ID, "deal", NOW.plus(10, ChronoUnit.DAYS)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");
  }

  @Test
  void effectivePlanNameForPharmacyAndCancelled() {
    when(plans.findAccountByPharmacyId(pharmacyId)).thenReturn(Optional.empty());
    assertThat(service.effectivePlanName(pharmacyId)).isEqualTo(PlanNames.FREE);

    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.STARTER, SubscriptionStatus.CANCELLED, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.CANCELLED)));
    assertThat(service.effectivePlanName(pharmacyId)).isEqualTo(PlanNames.FREE);

    SaasSubscription badOverride =
        new SaasSubscription(
            subId,
            accountId,
            FREE_ID,
            null,
            SubscriptionStatus.ACTIVE,
            BillingCycle.MONTHLY,
            NOW.plus(5, ChronoUnit.DAYS),
            null,
            true,
            null,
            null,
            null,
            null,
            null,
            RETAIL_ID,
            NOW.plus(5, ChronoUnit.DAYS),
            "x",
            NOW,
            NOW);
    when(plans.findPlanById(RETAIL_ID)).thenReturn(Optional.empty());
    assertThat(service.effectivePlanName(badOverride, NOW)).isEqualTo(PlanNames.FREE);
  }

  @Test
  void autoRenewSkipsCancelAndFreeAndRethrows() {
    SaasSubscription cancelling =
        new SaasSubscription(
            subId,
            accountId,
            STARTER_ID,
            null,
            SubscriptionStatus.ACTIVE,
            BillingCycle.MONTHLY,
            NOW.plus(2, ChronoUnit.DAYS),
            null,
            true,
            NOW,
            NOW.plus(2, ChronoUnit.DAYS),
            null,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    SaasSubscription freeSub = sub(FREE_ID, SubscriptionStatus.ACTIVE);
    when(subs.findDueForAutoRenew(any(), any())).thenReturn(List.of(cancelling, freeSub));
    when(plans.findPlanById(FREE_ID)).thenReturn(Optional.of(plan(FREE_ID, PlanNames.FREE, 0)));
    when(subs.findPastDueExpired(any())).thenReturn(List.of());
    when(subs.findTrialsEnding(any())).thenReturn(List.of());
    when(subs.findCancelsDue(any())).thenReturn(List.of());
    when(subs.findOverridesExpired(any())).thenReturn(List.of());
    service.processScheduledJobs();
    verify(payments, never()).charge(any(), anyLong(), anyString(), anyString());

    when(subs.findDueForAutoRenew(any(), any()))
        .thenReturn(List.of(sub(STARTER_ID, SubscriptionStatus.ACTIVE)));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    when(payments.charge(any(), anyLong(), anyString(), anyString()))
        .thenThrow(new AppException("OTHER", "x", 500));
    assertThatThrownBy(() -> service.processScheduledJobs())
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("OTHER");
  }

  @Test
  void pastDueSkipOverrideAndMutableExpired() {
    SaasSubscription pastDue =
        new SaasSubscription(
            subId,
            accountId,
            STARTER_ID,
            null,
            SubscriptionStatus.PAST_DUE,
            BillingCycle.MONTHLY,
            NOW,
            null,
            true,
            null,
            null,
            null,
            NOW.minus(10, ChronoUnit.DAYS),
            null,
            RETAIL_ID,
            NOW.plus(5, ChronoUnit.DAYS),
            "o",
            NOW,
            NOW);
    when(subs.findDueForAutoRenew(any(), any())).thenReturn(List.of());
    when(subs.findPastDueExpired(any())).thenReturn(List.of(pastDue));
    when(subs.findTrialsEnding(any())).thenReturn(List.of());
    when(subs.findCancelsDue(any())).thenReturn(List.of());
    when(subs.findOverridesExpired(any())).thenReturn(List.of());
    service.processScheduledJobs();
    verify(subs, never()).update(any());

    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.FREE, SubscriptionStatus.EXPIRED, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(FREE_ID, SubscriptionStatus.EXPIRED)));
    assertThatThrownBy(() -> service.cancel(owner))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SUBSCRIPTION_NOT_ACTIVE");
  }

  private SaasSubscription sub(UUID planId, String status) {
    return new SaasSubscription(
        subId,
        accountId,
        planId,
        null,
        status,
        BillingCycle.MONTHLY,
        NOW.plus(20, ChronoUnit.DAYS),
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

  private static SaasPlan plan(UUID id, String name, long paise) {
    return new SaasPlan(id, name, paise, 2, 500, true, false, NOW);
  }
}

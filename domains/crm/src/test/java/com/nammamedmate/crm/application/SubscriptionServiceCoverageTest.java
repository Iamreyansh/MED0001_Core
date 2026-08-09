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
import com.nammamedmate.crm.application.port.out.InvoiceIssuingPort;
import com.nammamedmate.crm.application.port.out.PharmacyPlanSyncPort;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.application.port.out.SaasRenewalChurnStore;
import com.nammamedmate.crm.application.port.out.SaasSubscriptionIdempotencyStore;
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
class SubscriptionServiceCoverageTest {

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
    lenient().when(plans.listActiveAddons()).thenReturn(List.of());
    lenient().when(idempotency.findByKey(anyString())).thenReturn(Optional.empty());
    lenient()
        .when(
            invoices.issue(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(inv -> Ids.newId());
  }

  @Test
  void getCurrentWithAddonAndStaff() {
    MedmatePrincipal staff =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_STAFF, pharmacyId, TokenScope.FULL, "s");
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.STARTER, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.ACTIVE)));
    when(plans.findPlanByName(PlanNames.STARTER))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    UUID addonId = Ids.newId();
    when(plans.listActiveAddons())
        .thenReturn(List.of(new SaasAddon(addonId, "E_INVOICE", 19900, "e", true)));
    when(plans.findActiveAccountAddon(accountId, addonId))
        .thenReturn(Optional.of(new AccountAddon(accountId, addonId, NOW, null)));
    when(plans.moduleCodesForPlan(PlanNames.STARTER)).thenReturn(List.of("INVENTORY"));

    Map<String, Object> data = service.getCurrent(staff);
    assertThat(data).containsEntry("plan", PlanNames.STARTER);
    assertThat((List<?>) data.get("addons")).hasSize(1);
  }

  @Test
  void autoRenewSuccessAndTrialEndAndCancelDue() {
    SaasSubscription due =
        new SaasSubscription(
            subId,
            accountId,
            STARTER_ID,
            RETAIL_ID,
            SubscriptionStatus.ACTIVE,
            BillingCycle.MONTHLY,
            NOW.plus(2, ChronoUnit.DAYS),
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
    when(subs.findDueForAutoRenew(any(), any())).thenReturn(List.of(due));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    when(plans.findPlanById(RETAIL_ID))
        .thenReturn(Optional.of(plan(RETAIL_ID, PlanNames.RETAIL_PRO, 149900)));
    when(payments.charge(any(), anyLong(), anyString(), anyString())).thenReturn(Ids.newId());
    when(subs.findPharmacyId(accountId)).thenReturn(Optional.of(pharmacyId));

    SaasSubscription trial =
        new SaasSubscription(
            Ids.newId(),
            accountId,
            STARTER_ID,
            null,
            SubscriptionStatus.TRIAL,
            BillingCycle.MONTHLY,
            NOW,
            NOW.minus(1, ChronoUnit.HOURS),
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
    when(subs.findPastDueExpired(any())).thenReturn(List.of());
    when(subs.findTrialsEnding(any())).thenReturn(List.of(trial));
    when(plans.findPlanByName(PlanNames.FREE))
        .thenReturn(Optional.of(plan(FREE_ID, PlanNames.FREE, 0)));

    SaasSubscription cancelDue =
        new SaasSubscription(
            Ids.newId(),
            accountId,
            STARTER_ID,
            null,
            SubscriptionStatus.ACTIVE,
            BillingCycle.MONTHLY,
            NOW,
            null,
            false,
            NOW.minus(1, ChronoUnit.DAYS),
            NOW.minus(1, ChronoUnit.HOURS),
            null,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    when(subs.findCancelsDue(any())).thenReturn(List.of(cancelDue));
    when(subs.findOverridesExpired(any())).thenReturn(List.of());

    service.processScheduledJobs();
    verify(payments).charge(eq(accountId), anyLong(), anyString(), anyString());
  }

  @Test
  void overrideExpiredClearsAndEffectiveOverride() {
    SaasSubscription withOverride =
        new SaasSubscription(
            subId,
            accountId,
            FREE_ID,
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
            RETAIL_ID,
            NOW.plus(10, ChronoUnit.DAYS),
            "deal",
            NOW,
            NOW);
    when(plans.findPlanById(RETAIL_ID))
        .thenReturn(Optional.of(plan(RETAIL_ID, PlanNames.RETAIL_PRO, 149900)));
    assertThat(service.effectivePlanName(withOverride, NOW)).isEqualTo(PlanNames.RETAIL_PRO);

    SaasSubscription expiredOverride =
        new SaasSubscription(
            subId,
            accountId,
            STARTER_ID,
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
            NOW.minus(1, ChronoUnit.HOURS),
            "old",
            NOW,
            NOW);
    when(subs.findDueForAutoRenew(any(), any())).thenReturn(List.of());
    when(subs.findPastDueExpired(any())).thenReturn(List.of());
    when(subs.findTrialsEnding(any())).thenReturn(List.of());
    when(subs.findCancelsDue(any())).thenReturn(List.of());
    when(subs.findOverridesExpired(any())).thenReturn(List.of(expiredOverride));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    when(subs.findPharmacyId(accountId)).thenReturn(Optional.of(pharmacyId));
    service.processScheduledJobs();
    verify(subs).update(any());
  }

  @Test
  void upgradeDowngradeGuardsAndBillingCycle() {
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.RETAIL_PRO, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(RETAIL_ID, SubscriptionStatus.ACTIVE)));
    when(plans.findPlanById(RETAIL_ID))
        .thenReturn(Optional.of(plan(RETAIL_ID, PlanNames.RETAIL_PRO, 149900)));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    assertThatThrownBy(() -> service.upgrade(owner, STARTER_ID, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DOWNGRADE_NOT_ALLOWED");
    assertThatThrownBy(() -> service.downgrade(owner, RETAIL_ID))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(BillingCycle.requireValid(null)).isEqualTo(BillingCycle.MONTHLY);
    assertThatThrownBy(() -> BillingCycle.requireValid("WEEKLY"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void startStarterTrialAndSchedulerRun() {
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.FREE, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(FREE_ID, SubscriptionStatus.ACTIVE)));
    when(plans.findPlanByName(PlanNames.STARTER))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    SaasSubscription trial = service.startStarterTrial(pharmacyId);
    assertThat(trial.status()).isEqualTo(SubscriptionStatus.TRIAL);

    when(subs.findDueForAutoRenew(any(), any())).thenReturn(List.of());
    when(subs.findPastDueExpired(any())).thenReturn(List.of());
    when(subs.findTrialsEnding(any())).thenReturn(List.of());
    when(subs.findCancelsDue(any())).thenReturn(List.of());
    when(subs.findOverridesExpired(any())).thenReturn(List.of());
    new SubscriptionRenewalScheduler(service).run();
  }

  @Test
  void ensureNullPharmacyAndForbidden() {
    service.ensureFreeSubscription(null);
    assertThatThrownBy(() -> service.subscribe(null, STARTER_ID, "MONTHLY", null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.getCurrent(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.getCurrent(
                    new MedmatePrincipal(
                        Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "a")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void prorateHelpers() {
    long[] r =
        SubscriptionService.prorateUpgrade(
            69900, 149900, BillingCycle.MONTHLY, NOW, NOW.plus(15, ChronoUnit.DAYS));
    assertThat(r[0]).isGreaterThan(0);
    assertThat(r[1]).isGreaterThan(0);
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

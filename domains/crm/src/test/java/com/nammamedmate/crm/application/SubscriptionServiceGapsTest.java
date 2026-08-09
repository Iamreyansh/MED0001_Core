package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
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
class SubscriptionServiceGapsTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID FREE_ID = UUID.fromString("a1000000-0000-4000-8000-000000000001");
  private static final UUID STARTER_ID = UUID.fromString("a1000000-0000-4000-8000-000000000002");

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
  void remainingBranches() {
    // WELCOME coupon + annual proration
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.FREE, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(FREE_ID, SubscriptionStatus.ACTIVE, null)));
    when(plans.findPlanById(FREE_ID)).thenReturn(Optional.of(plan(FREE_ID, PlanNames.FREE, 0)));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    when(payments.charge(any(), anyLong(), anyString(), anyString())).thenReturn(Ids.newId());
    UUID addonOnSubscribe = Ids.newId();
    when(plans.listActiveAddons())
        .thenReturn(List.of(new SaasAddon(addonOnSubscribe, "E_INVOICE", 19900, "e", true)));
    when(plans.findActiveAccountAddon(accountId, addonOnSubscribe))
        .thenReturn(
            Optional.of(
                new com.nammamedmate.crm.domain.AccountAddon(
                    accountId, addonOnSubscribe, NOW.minusSeconds(5), null)));
    assertThat(service.subscribe(owner, STARTER_ID, BillingCycle.ANNUAL, "WELCOME", "k"))
        .containsEntry("billing_cycle", BillingCycle.ANNUAL);
    assertThat(
            SubscriptionService.prorateUpgrade(
                100, 200, BillingCycle.ANNUAL, NOW, NOW.plus(100, ChronoUnit.DAYS))[1])
        .isGreaterThanOrEqualTo(0);

    // getCurrent with inactive addon row
    when(plans.findPlanByName(PlanNames.STARTER))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.ACTIVE, null)));
    UUID addonId = Ids.newId();
    when(plans.listActiveAddons())
        .thenReturn(List.of(new SaasAddon(addonId, "E_INVOICE", 19900, "e", true)));
    when(plans.findActiveAccountAddon(accountId, addonId)).thenReturn(Optional.empty());
    when(plans.moduleCodesForPlan(PlanNames.STARTER)).thenReturn(List.of());
    assertThat(service.getCurrent(owner).get("addons")).asList().isEmpty();

    // override principal null + reason null + CANCELLED reactivation
    assertThatThrownBy(
            () ->
                service.overrideSubscription(
                    null, accountId, STARTER_ID, "x", NOW.plus(5, ChronoUnit.DAYS)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                service.overrideSubscription(
                    admin, accountId, STARTER_ID, null, NOW.plus(5, ChronoUnit.DAYS)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(plans.findAccountById(accountId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.FREE, SubscriptionStatus.CANCELLED, NOW)));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(FREE_ID, SubscriptionStatus.CANCELLED, null)));
    assertThat(
            service.overrideSubscription(
                admin, accountId, STARTER_ID, "deal", NOW.plus(5, ChronoUnit.DAYS)))
        .containsEntry("override_plan", PlanNames.STARTER);

    // auto-renew: missing plan + success without schedule
    SaasSubscription missingPlan = sub(STARTER_ID, SubscriptionStatus.ACTIVE, null);
    when(subs.findDueForAutoRenew(any(), any())).thenReturn(List.of(missingPlan));
    when(plans.findPlanById(STARTER_ID)).thenReturn(Optional.empty());
    when(subs.findPastDueExpired(any())).thenReturn(List.of());
    when(subs.findTrialsEnding(any())).thenReturn(List.of());
    when(subs.findCancelsDue(any())).thenReturn(List.of());
    when(subs.findOverridesExpired(any())).thenReturn(List.of());
    service.processScheduledJobs();

    SaasSubscription renewOk = sub(STARTER_ID, SubscriptionStatus.ACTIVE, null);
    when(subs.findDueForAutoRenew(any(), any())).thenReturn(List.of(renewOk));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    when(payments.charge(any(), anyLong(), anyString(), anyString())).thenReturn(Ids.newId());
    when(subs.findPharmacyId(accountId)).thenReturn(Optional.empty());
    service.processScheduledJobs();

    // FREE missing on expire/trial/cancel
    when(subs.findDueForAutoRenew(any(), any())).thenReturn(List.of());
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
            null,
            null,
            null,
            NOW,
            NOW);
    when(subs.findPastDueExpired(any())).thenReturn(List.of(pastDue));
    when(plans.findPlanByName(PlanNames.FREE)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.processScheduledJobs())
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");

    when(subs.findPastDueExpired(any())).thenReturn(List.of());
    when(subs.findTrialsEnding(any()))
        .thenReturn(List.of(sub(STARTER_ID, SubscriptionStatus.TRIAL, null)));
    assertThatThrownBy(() -> service.processScheduledJobs())
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");

    when(subs.findTrialsEnding(any())).thenReturn(List.of());
    when(subs.findCancelsDue(any()))
        .thenReturn(List.of(sub(STARTER_ID, SubscriptionStatus.ACTIVE, NOW)));
    assertThatThrownBy(() -> service.processScheduledJobs())
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");

    when(plans.findPlanByName(PlanNames.STARTER)).thenReturn(Optional.empty());
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(FREE_ID, SubscriptionStatus.ACTIVE, null)));
    assertThatThrownBy(() -> service.startStarterTrial(pharmacyId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");

    // requireAccount / requireOwner branches
    when(plans.findAccountByPharmacyId(pharmacyId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.cancel(owner))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ACCOUNT_NOT_FOUND");
    MedmatePrincipal staff =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_STAFF, pharmacyId, TokenScope.FULL, "s");
    assertThatThrownBy(() -> service.cancel(staff))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    MedmatePrincipal noPharm =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "o");
    assertThatThrownBy(() -> service.cancel(noPharm))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    // blank coupon
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.FREE, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(FREE_ID, SubscriptionStatus.ACTIVE, null)));
    when(plans.findPlanById(FREE_ID)).thenReturn(Optional.of(plan(FREE_ID, PlanNames.FREE, 0)));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    assertThat(service.subscribe(owner, STARTER_ID, "MONTHLY", "  ", "k"))
        .containsKey("subscription_id");

    // ALREADY_SUBSCRIBED compound: PAST_DUE paid is mutable path through subscribe check
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.PAST_DUE, null)));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    when(payments.charge(any(), anyLong(), anyString(), anyString())).thenReturn(Ids.newId());
    assertThat(service.subscribe(owner, STARTER_ID, "MONTHLY", null, "k2"))
        .containsEntry("status", "ACTIVE");

    assertThatThrownBy(() -> service.subscribe(owner, STARTER_ID, "MONTHLY", null, " "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.subscribe(owner, STARTER_ID, "MONTHLY", null, "x".repeat(129)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(idempotency.findByKey("bad-op"))
        .thenReturn(
            Optional.of(
                new SaasSubscriptionIdempotencyStore.CachedResponse(
                    "bad-op", accountId, SaasSubscriptionIdempotencyStore.OP_UPGRADE, "{}")));
    assertThatThrownBy(() -> service.subscribe(owner, STARTER_ID, "MONTHLY", null, "bad-op"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(idempotency.findByKey("bad-acct"))
        .thenReturn(
            Optional.of(
                new SaasSubscriptionIdempotencyStore.CachedResponse(
                    "bad-acct", Ids.newId(), SaasSubscriptionIdempotencyStore.OP_SUBSCRIBE, "{}")));
    assertThatThrownBy(() -> service.subscribe(owner, STARTER_ID, "MONTHLY", null, "bad-acct"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(idempotency.findByKey("bad-json"))
        .thenReturn(
            Optional.of(
                new SaasSubscriptionIdempotencyStore.CachedResponse(
                    "bad-json", accountId, SaasSubscriptionIdempotencyStore.OP_SUBSCRIBE, "{")));
    assertThatThrownBy(() -> service.subscribe(owner, STARTER_ID, "MONTHLY", null, "bad-json"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INTERNAL_ERROR");
    org.mockito.Mockito.doThrow(new RuntimeException("db"))
        .when(idempotency)
        .insert(anyString(), any(), anyString(), anyString(), any());
    when(idempotency.findByKey("insert-fail")).thenReturn(Optional.empty());
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(FREE_ID, SubscriptionStatus.ACTIVE, null)));
    when(plans.findPlanById(FREE_ID)).thenReturn(Optional.of(plan(FREE_ID, PlanNames.FREE, 0)));
    assertThatThrownBy(() -> service.subscribe(owner, STARTER_ID, "MONTHLY", null, "insert-fail"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INTERNAL_ERROR");
    org.mockito.Mockito.reset(idempotency);
    lenient().when(idempotency.findByKey(anyString())).thenReturn(Optional.empty());

    when(idempotency.findByKey("up-replay"))
        .thenReturn(
            Optional.of(
                new SaasSubscriptionIdempotencyStore.CachedResponse(
                    "up-replay",
                    accountId,
                    SaasSubscriptionIdempotencyStore.OP_UPGRADE,
                    "{\"new_plan\":\"RETAIL_PRO\"}")));
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.STARTER, SubscriptionStatus.ACTIVE, NOW)));
    assertThat(service.upgrade(owner, STARTER_ID, "up-replay"))
        .containsEntry("new_plan", "RETAIL_PRO");

    // requireSub missing on cancel
    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.STARTER, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.cancel(owner))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SUBSCRIPTION_NOT_FOUND");

    // requireMutable CANCELLED
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.CANCELLED, null)));
    assertThatThrownBy(() -> service.cancel(owner))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SUBSCRIPTION_NOT_ACTIVE");

    // effectivePlanName CANCELLED vs EXPIRED arms
    assertThat(service.effectivePlanName(sub(STARTER_ID, SubscriptionStatus.EXPIRED, null), NOW))
        .isEqualTo(PlanNames.FREE);

    // getCurrent staff path already covered; finance forbidden both role checks
    MedmatePrincipal finance =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, pharmacyId, TokenScope.FULL, "f");
    assertThatThrownBy(() -> service.getCurrent(finance))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void subscribeOrUpgradeForPharmacyBranches() {
    assertThatThrownBy(
            () -> service.subscribeOrUpgradeForPharmacy(null, STARTER_ID, "MONTHLY", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(plans.findAccountByPharmacyId(pharmacyId))
        .thenReturn(
            Optional.of(
                new CrmAccount(
                    accountId, pharmacyId, PlanNames.FREE, SubscriptionStatus.ACTIVE, NOW)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(FREE_ID, SubscriptionStatus.ACTIVE, null)));
    when(plans.findPlanById(FREE_ID)).thenReturn(Optional.of(plan(FREE_ID, PlanNames.FREE, 0)));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));

    var subscribed =
        service.subscribeOrUpgradeForPharmacy(pharmacyId, STARTER_ID, "MONTHLY", "lead-won:1");
    assertThat(subscribed.get("subscription_id")).isEqualTo(subId);

    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(FREE_ID, SubscriptionStatus.CANCELLED, null)));
    when(plans.findPlanById(FREE_ID)).thenReturn(Optional.of(plan(FREE_ID, PlanNames.FREE, 0)));
    assertThat(
            service
                .subscribeOrUpgradeForPharmacy(pharmacyId, STARTER_ID, "MONTHLY", "   ")
                .get("subscription_id"))
        .isEqualTo(subId);

    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.ACTIVE, null)));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    var same = service.subscribeOrUpgradeForPharmacy(pharmacyId, STARTER_ID, "MONTHLY", null);
    assertThat(same.get("plan")).isEqualTo(PlanNames.STARTER);

    UUID retailId = UUID.fromString("a1000000-0000-4000-8000-000000000003");
    when(plans.findPlanById(retailId))
        .thenReturn(Optional.of(plan(retailId, PlanNames.RETAIL_PRO, 149900)));
    var upgraded = service.subscribeOrUpgradeForPharmacy(pharmacyId, retailId, "MONTHLY", null);
    assertThat(upgraded.get("subscription_id")).isEqualTo(subId);

    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(retailId, SubscriptionStatus.ACTIVE, null)));
    when(plans.findPlanById(retailId))
        .thenReturn(Optional.of(plan(retailId, PlanNames.RETAIL_PRO, 149900)));
    var lower = service.subscribeOrUpgradeForPharmacy(pharmacyId, STARTER_ID, "MONTHLY", null);
    assertThat(lower.get("plan")).isEqualTo(PlanNames.RETAIL_PRO);

    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.TRIAL, null)));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    assertThat(
            service
                .subscribeOrUpgradeForPharmacy(pharmacyId, STARTER_ID, "MONTHLY", null)
                .get("status"))
        .isEqualTo(SubscriptionStatus.TRIAL);

    // same plan id but not paid-active → resubscribe path
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.PAST_DUE, null)));
    when(plans.findPlanById(retailId))
        .thenReturn(Optional.of(plan(retailId, PlanNames.RETAIL_PRO, 149900)));
    assertThat(service.subscribeOrUpgradeForPharmacy(pharmacyId, STARTER_ID, "MONTHLY", null))
        .containsKey("subscription_id");

    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(FREE_ID, SubscriptionStatus.ACTIVE, null)));
    when(plans.findPlanById(FREE_ID)).thenReturn(Optional.of(plan(FREE_ID, PlanNames.FREE, 0)));
    when(plans.findPlanById(STARTER_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> service.subscribeOrUpgradeForPharmacy(pharmacyId, STARTER_ID, "MONTHLY", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");

    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(FREE_ID, SubscriptionStatus.ACTIVE, null)));
    when(plans.findPlanById(FREE_ID)).thenReturn(Optional.empty());
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    assertThatThrownBy(
            () -> service.subscribeOrUpgradeForPharmacy(pharmacyId, STARTER_ID, "MONTHLY", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_NOT_FOUND");

    when(plans.findPlanById(FREE_ID)).thenReturn(Optional.of(plan(FREE_ID, PlanNames.FREE, 0)));
    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.PAST_DUE, null)));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    when(plans.findPlanById(retailId))
        .thenReturn(Optional.of(plan(retailId, PlanNames.RETAIL_PRO, 149900)));
    assertThat(service.subscribeOrUpgradeForPharmacy(pharmacyId, retailId, "MONTHLY", null))
        .containsKey("subscription_id");

    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.CANCELLED, null)));
    when(plans.findPlanById(STARTER_ID))
        .thenReturn(Optional.of(plan(STARTER_ID, PlanNames.STARTER, 69900)));
    assertThat(service.subscribeOrUpgradeForPharmacy(pharmacyId, retailId, "MONTHLY", null))
        .containsKey("subscription_id");

    when(subs.findByAccountId(accountId))
        .thenReturn(Optional.of(sub(STARTER_ID, SubscriptionStatus.EXPIRED, null)));
    assertThat(service.subscribeOrUpgradeForPharmacy(pharmacyId, retailId, "MONTHLY", null))
        .containsKey("subscription_id");
  }

  private SaasSubscription sub(UUID planId, String status, Instant cancelsAt) {
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
        cancelsAt,
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

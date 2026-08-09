package com.nammamedmate.crm.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.crm.application.port.out.CrmAuditPort;
import com.nammamedmate.crm.application.port.out.CrmSubscriptionOutboxPort;
import com.nammamedmate.crm.application.port.out.EnsureFreeSubscriptionPort;
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
import com.nammamedmate.crm.domain.CrmMoney;
import com.nammamedmate.crm.domain.InvoiceLineItemType;
import com.nammamedmate.crm.domain.InvoiceStatus;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService implements EnsureFreeSubscriptionPort {

  static final int TRIAL_DAYS = 14;
  static final int PAST_DUE_GRACE_DAYS = 7;
  static final int AUTO_RENEW_LEAD_DAYS = 3;
  static final int OVERRIDE_MAX_DAYS = 90;

  private final SaasPlanStore plans;
  private final SaasSubscriptionStore subs;
  private final SubscriptionPaymentPort payments;
  private final InvoiceIssuingPort invoices;
  private final PharmacyPlanSyncPort planSync;
  private final CrmSubscriptionOutboxPort outbox;
  private final CrmAuditPort audit;
  private final SaasRenewalChurnStore cohorts;
  private final SaasSubscriptionIdempotencyStore idempotency;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public SubscriptionService(
      SaasPlanStore plans,
      SaasSubscriptionStore subs,
      SubscriptionPaymentPort payments,
      InvoiceIssuingPort invoices,
      PharmacyPlanSyncPort planSync,
      CrmSubscriptionOutboxPort outbox,
      CrmAuditPort audit,
      SaasRenewalChurnStore cohorts,
      SaasSubscriptionIdempotencyStore idempotency,
      ObjectMapper objectMapper,
      Clock clock) {
    this.plans = plans;
    this.subs = subs;
    this.payments = payments;
    this.invoices = invoices;
    this.planSync = planSync;
    this.outbox = outbox;
    this.audit = audit;
    this.cohorts = cohorts;
    this.idempotency = idempotency;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  @Transactional
  public void ensureFreeSubscription(UUID pharmacyId) {
    if (pharmacyId == null) {
      return;
    }
    Instant now = clock.instant();
    CrmAccount account =
        plans
            .findAccountByPharmacyId(pharmacyId)
            .orElseGet(
                () ->
                    plans.createAccount(
                        pharmacyId, PlanNames.FREE, SubscriptionStatus.ACTIVE, now));
    if (subs.findByAccountId(account.id()).isPresent()) {
      return;
    }
    SaasPlan free =
        plans
            .findPlanByName(PlanNames.FREE)
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "FREE plan missing", 404));
    SaasSubscription sub =
        new SaasSubscription(
            Ids.newId(),
            account.id(),
            free.id(),
            null,
            SubscriptionStatus.ACTIVE,
            BillingCycle.MONTHLY,
            BillingCycle.advance(now, BillingCycle.MONTHLY),
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
            now,
            now);
    subs.insert(sub);
    syncDenormAndPharmacy(account.id(), pharmacyId, PlanNames.FREE, SubscriptionStatus.ACTIVE, now);
  }

  @Transactional
  public Map<String, Object> subscribe(
      MedmatePrincipal principal,
      UUID planId,
      String billingCycleRaw,
      String couponCode,
      String idempotencyKey) {
    requireOwner(principal);
    String key = requireIdempotencyKey(idempotencyKey);
    Instant now = clock.instant();
    ensureFreeSubscription(principal.pharmacyId());
    CrmAccount account = requireAccount(principal.pharmacyId());
    Optional<Map<String, Object>> replay =
        replayCached(key, account.id(), SaasSubscriptionIdempotencyStore.OP_SUBSCRIBE);
    if (replay.isPresent()) {
      return replay.get();
    }
    SaasSubscription current =
        subs.findByAccountId(account.id())
            .orElseThrow(
                () -> new AppException("SUBSCRIPTION_NOT_FOUND", "Subscription not found", 404));
    SaasPlan currentPlan =
        plans
            .findPlanById(current.planId())
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404));
    if (!PlanNames.FREE.equals(currentPlan.name())
        && (SubscriptionStatus.ACTIVE.equals(current.status())
            || SubscriptionStatus.TRIAL.equals(current.status()))) {
      throw new AppException(
          "ALREADY_SUBSCRIBED", "Pharmacy already has an active subscription", 409);
    }
    SaasPlan target =
        plans
            .findPlanById(planId)
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404));
    if (PlanNames.FREE.equals(target.name())) {
      throw new AppException("VALIDATION_ERROR", "Use cancel/downgrade to move to FREE", 400);
    }
    validateCoupon(couponCode);
    String cycle = BillingCycle.requireValid(billingCycleRaw);
    long planPaise = BillingCycle.cyclePricePaise(target.priceMonthlyPaise(), cycle);
    Instant renewal = BillingCycle.advance(now, cycle);
    LocalDate periodFrom = LocalDate.ofInstant(now, ZoneOffset.UTC);
    LocalDate periodTo = LocalDate.ofInstant(renewal, ZoneOffset.UTC);
    List<InvoiceIssuingPort.LineDraft> lines =
        billingLines(account.id(), target, cycle, planPaise, 0L);
    long amountPaise = sumPaise(lines);
    payments.charge(account.id(), amountPaise, "subscribe:" + target.name(), key);
    UUID invoiceId =
        invoices.issue(
            account.id(),
            current.id(),
            target.name(),
            periodFrom,
            periodTo,
            periodFrom,
            lines,
            InvoiceStatus.PAID,
            now,
            "UPI",
            "subscribe:" + target.name());
    Instant trialEnds = null;
    String status = SubscriptionStatus.ACTIVE;
    // STARTER subscribe is paid ACTIVE per AC-002; trial is separate admin/flow grant.
    SaasSubscription updated =
        withPlan(current, target.id(), status, cycle, renewal, trialEnds, invoiceId, now);
    updated =
        new SaasSubscription(
            updated.id(),
            updated.accountId(),
            updated.planId(),
            null,
            updated.status(),
            updated.billingCycle(),
            updated.renewalDate(),
            updated.trialEndsAt(),
            true,
            null,
            null,
            null,
            null,
            updated.lastInvoiceId(),
            updated.overridePlanId(),
            updated.overrideExpiresAt(),
            updated.overrideReason(),
            updated.createdAt(),
            now);
    subs.update(updated);
    syncDenormAndPharmacy(account.id(), account.pharmacyId(), target.name(), status, now);
    cohorts.ensureCohort(
        account.id(), LocalDate.ofInstant(now, ZoneOffset.UTC).withDayOfMonth(1), now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("subscription_id", updated.id());
    data.put("plan", target.name());
    data.put("billing_cycle", cycle);
    data.put("status", status);
    data.put("renewal_date", renewal);
    data.put("invoice_id", invoiceId);
    data.put("amount_charged_rs", CrmMoney.paiseToRupees(amountPaise));
    cacheResponse(key, account.id(), SaasSubscriptionIdempotencyStore.OP_SUBSCRIBE, data, now);
    return data;
  }

  /**
   * Admin lead conversion: subscribe from FREE or upgrade when already on a lower paid plan.
   * Returns a map that always includes {@code subscription_id}.
   */
  @Transactional
  public Map<String, Object> subscribeOrUpgradeForPharmacy(
      UUID pharmacyId, UUID planId, String billingCycleRaw, String idempotencyKey) {
    if (pharmacyId == null) {
      throw new AppException("VALIDATION_ERROR", "pharmacy_id required for lead conversion", 422);
    }
    ensureFreeSubscription(pharmacyId);
    MedmatePrincipal owner =
        new MedmatePrincipal(
            pharmacyId, AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "crm-lead");
    CrmAccount account = requireAccount(pharmacyId);
    SaasSubscription current = requireSub(account.id());
    SaasPlan currentPlan =
        plans
            .findPlanById(current.planId())
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404));
    SaasPlan target =
        plans
            .findPlanById(planId)
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404));
    boolean paidActive =
        SubscriptionStatus.ACTIVE.equals(current.status())
            || SubscriptionStatus.TRIAL.equals(current.status());
    if (current.planId().equals(planId) && paidActive) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("subscription_id", current.id());
      data.put("plan", currentPlan.name());
      data.put("billing_cycle", current.billingCycle());
      data.put("status", current.status());
      return data;
    }
    String key =
        (idempotencyKey == null || idempotencyKey.isBlank())
            ? "internal:" + account.id() + ":" + planId
            : idempotencyKey;
    boolean resubscribe =
        PlanNames.FREE.equals(currentPlan.name())
            || SubscriptionStatus.CANCELLED.equals(current.status())
            || SubscriptionStatus.EXPIRED.equals(current.status())
            || SubscriptionStatus.PAST_DUE.equals(current.status());
    if (resubscribe) {
      return subscribe(owner, planId, billingCycleRaw, null, key);
    }
    if (PlanNames.tierIndex(target.name()) > PlanNames.tierIndex(currentPlan.name())) {
      Map<String, Object> upgraded = upgrade(owner, planId, key);
      upgraded.put("subscription_id", current.id());
      return upgraded;
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("subscription_id", current.id());
    data.put("plan", currentPlan.name());
    data.put("billing_cycle", current.billingCycle());
    data.put("status", current.status());
    return data;
  }

  @Transactional
  public Map<String, Object> upgrade(
      MedmatePrincipal principal, UUID newPlanId, String idempotencyKey) {
    requireOwner(principal);
    String key = requireIdempotencyKey(idempotencyKey);
    Instant now = clock.instant();
    CrmAccount account = requireAccount(principal.pharmacyId());
    Optional<Map<String, Object>> replay =
        replayCached(key, account.id(), SaasSubscriptionIdempotencyStore.OP_UPGRADE);
    if (replay.isPresent()) {
      return replay.get();
    }
    SaasSubscription current = requireSub(account.id());
    requireMutable(current);
    SaasPlan oldPlan =
        plans
            .findPlanById(current.planId())
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404));
    SaasPlan newPlan =
        plans
            .findPlanById(newPlanId)
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404));
    if (PlanNames.tierIndex(newPlan.name()) <= PlanNames.tierIndex(oldPlan.name())) {
      throw new AppException("DOWNGRADE_NOT_ALLOWED", "Use downgrade endpoint instead", 400);
    }
    long[] proration =
        prorateUpgrade(
            oldPlan.priceMonthlyPaise(),
            newPlan.priceMonthlyPaise(),
            current.billingCycle(),
            now,
            current.renewalDate());
    long creditPaise = proration[0];
    long chargePaise = proration[1];
    LocalDate periodFrom = LocalDate.ofInstant(now, ZoneOffset.UTC);
    LocalDate periodTo = LocalDate.ofInstant(current.renewalDate(), ZoneOffset.UTC);
    List<InvoiceIssuingPort.LineDraft> lines =
        billingLines(
            account.id(), newPlan, current.billingCycle(), chargePaise + creditPaise, creditPaise);
    payments.charge(account.id(), sumPaise(lines), "upgrade:" + newPlan.name(), key);
    UUID invoiceId =
        invoices.issue(
            account.id(),
            current.id(),
            newPlan.name(),
            periodFrom,
            periodTo,
            periodFrom,
            lines,
            InvoiceStatus.PAID,
            now,
            "UPI",
            "upgrade:" + newPlan.name());
    SaasSubscription updated =
        withPlan(
            current,
            newPlan.id(),
            SubscriptionStatus.ACTIVE,
            current.billingCycle(),
            current.renewalDate(),
            null,
            invoiceId,
            now);
    updated =
        new SaasSubscription(
            updated.id(),
            updated.accountId(),
            updated.planId(),
            null,
            updated.status(),
            updated.billingCycle(),
            updated.renewalDate(),
            null,
            updated.autoRenew(),
            null,
            null,
            null,
            null,
            updated.lastInvoiceId(),
            updated.overridePlanId(),
            updated.overrideExpiresAt(),
            updated.overrideReason(),
            updated.createdAt(),
            now);
    subs.update(updated);
    syncDenormAndPharmacy(
        account.id(), account.pharmacyId(), newPlan.name(), SubscriptionStatus.ACTIVE, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("previous_plan", oldPlan.name());
    data.put("new_plan", newPlan.name());
    data.put("effective_immediately", true);
    data.put("prorated_credit_rs", CrmMoney.paiseToRupees(creditPaise));
    data.put("amount_charged_rs", CrmMoney.paiseToRupees(chargePaise));
    data.put("invoice_id", invoiceId);
    data.put("new_renewal_date", current.renewalDate());
    cacheResponse(key, account.id(), SaasSubscriptionIdempotencyStore.OP_UPGRADE, data, now);
    return data;
  }

  @Transactional
  public Map<String, Object> downgrade(MedmatePrincipal principal, UUID newPlanId) {
    requireOwner(principal);
    Instant now = clock.instant();
    CrmAccount account = requireAccount(principal.pharmacyId());
    SaasSubscription current = requireSub(account.id());
    requireMutable(current);
    SaasPlan oldPlan =
        plans
            .findPlanById(current.planId())
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404));
    SaasPlan newPlan =
        plans
            .findPlanById(newPlanId)
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404));
    if (PlanNames.tierIndex(newPlan.name()) >= PlanNames.tierIndex(oldPlan.name())) {
      throw new AppException("VALIDATION_ERROR", "Use upgrade endpoint for higher tiers", 400);
    }
    SaasSubscription updated =
        new SaasSubscription(
            current.id(),
            current.accountId(),
            current.planId(),
            newPlan.id(),
            current.status(),
            current.billingCycle(),
            current.renewalDate(),
            current.trialEndsAt(),
            current.autoRenew(),
            current.cancelledAt(),
            current.cancelsAt(),
            current.expiresAt(),
            current.pastDueAt(),
            current.lastInvoiceId(),
            current.overridePlanId(),
            current.overrideExpiresAt(),
            current.overrideReason(),
            current.createdAt(),
            now);
    subs.update(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("current_plan", oldPlan.name());
    data.put("scheduled_plan", newPlan.name());
    data.put("effective_date", current.renewalDate());
    data.put(
        "message",
        "Your plan will downgrade to "
            + newPlan.name()
            + " at the end of your current billing cycle.");
    return data;
  }

  @Transactional
  public Map<String, Object> cancel(MedmatePrincipal principal) {
    requireOwner(principal);
    Instant now = clock.instant();
    CrmAccount account = requireAccount(principal.pharmacyId());
    SaasSubscription current = requireSub(account.id());
    requireMutable(current);
    Instant cancelsAt = current.renewalDate();
    SaasSubscription updated =
        new SaasSubscription(
            current.id(),
            current.accountId(),
            current.planId(),
            current.scheduledPlanId(),
            current.status(),
            current.billingCycle(),
            current.renewalDate(),
            current.trialEndsAt(),
            false,
            now,
            cancelsAt,
            current.expiresAt(),
            current.pastDueAt(),
            current.lastInvoiceId(),
            current.overridePlanId(),
            current.overrideExpiresAt(),
            current.overrideReason(),
            current.createdAt(),
            now);
    subs.update(updated);
    outbox.publish(
        "crm.subscription.churn_survey",
        current.id(),
        Map.of(
            "subscription_id", current.id().toString(),
            "account_id", account.id().toString(),
            "pharmacy_id", account.pharmacyId().toString()));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("subscription_id", current.id());
    data.put("status", current.status());
    data.put("cancels_at", cancelsAt);
    data.put(
        "message",
        "Your subscription will remain active until " + cancelsAt + ". No further charges.");
    return data;
  }

  public Map<String, Object> getCurrent(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Pharmacy access required", 403);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("FORBIDDEN", "Pharmacy access required", 403);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.PHARMACY_OWNER && role != AuthRole.PHARMACY_STAFF) {
      throw new AppException("FORBIDDEN", "Pharmacy access required", 403);
    }
    Instant now = clock.instant();
    ensureFreeSubscription(principal.pharmacyId());
    CrmAccount account = requireAccount(principal.pharmacyId());
    SaasSubscription sub = requireSub(account.id());
    String effectivePlanName = effectivePlanName(sub, now);
    SaasPlan plan =
        plans
            .findPlanByName(effectivePlanName)
            .or(() -> plans.findPlanById(sub.planId()))
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404));
    List<Map<String, Object>> addons = new ArrayList<>();
    for (SaasAddon addon : plans.listActiveAddons()) {
      Optional<AccountAddon> aa = plans.findActiveAccountAddon(account.id(), addon.id());
      if (aa.isPresent()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", addon.name());
        row.put("price_monthly_rs", CrmMoney.paiseToRupees(addon.priceMonthlyPaise()));
        row.put("active_since", aa.get().effectiveFrom());
        addons.add(row);
      }
    }
    Map<String, Object> seat = new LinkedHashMap<>();
    seat.put("used", 0);
    seat.put("limit", plan.seatLimit());
    Map<String, Object> invoices = new LinkedHashMap<>();
    invoices.put("used", 0);
    invoices.put("limit", plan.invoiceCapMonthly());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("subscription_id", sub.id());
    data.put("plan", plan.name());
    data.put("status", sub.status());
    data.put("billing_cycle", sub.billingCycle());
    data.put("price_monthly_rs", CrmMoney.paiseToRupees(plan.priceMonthlyPaise()));
    data.put("renewal_date", sub.renewalDate());
    data.put("auto_renew", sub.autoRenew());
    data.put("seat_usage", seat);
    data.put("invoice_usage", invoices);
    data.put("modules_unlocked", plans.moduleCodesForPlan(plan.name()));
    data.put("addons", addons);
    return data;
  }

  @Transactional
  public Map<String, Object> setAutoRenew(MedmatePrincipal principal, boolean enabled) {
    requireOwner(principal);
    Instant now = clock.instant();
    CrmAccount account = requireAccount(principal.pharmacyId());
    SaasSubscription current = requireSub(account.id());
    SaasSubscription updated =
        new SaasSubscription(
            current.id(),
            current.accountId(),
            current.planId(),
            current.scheduledPlanId(),
            current.status(),
            current.billingCycle(),
            current.renewalDate(),
            current.trialEndsAt(),
            enabled,
            current.cancelledAt(),
            current.cancelsAt(),
            current.expiresAt(),
            current.pastDueAt(),
            current.lastInvoiceId(),
            current.overridePlanId(),
            current.overrideExpiresAt(),
            current.overrideReason(),
            current.createdAt(),
            now);
    subs.update(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("auto_renew", enabled);
    data.put(
        "message",
        enabled
            ? "Auto-renew enabled."
            : "Auto-renew disabled. Your subscription will expire on "
                + current.renewalDate()
                + ".");
    return data;
  }

  @Transactional
  public Map<String, Object> overrideSubscription(
      MedmatePrincipal principal,
      UUID accountId,
      UUID planId,
      String reason,
      Instant overrideExpiresAt) {
    if (principal == null || principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Only admin_super may override", 403);
    }
    Instant now = clock.instant();
    if (overrideExpiresAt == null) {
      throw new AppException("VALIDATION_ERROR", "override_expires_at required", 400);
    }
    if (!overrideExpiresAt.isAfter(now)) {
      throw new AppException("VALIDATION_ERROR", "override_expires_at must be in the future", 400);
    }
    if (Duration.between(now, overrideExpiresAt).toDays() > OVERRIDE_MAX_DAYS) {
      throw new AppException(
          "OVERRIDE_DURATION_EXCEEDED", "override_expires_at more than 90 days from now", 422);
    }
    if (reason == null || reason.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "override_reason required", 400);
    }
    CrmAccount account =
        plans
            .findAccountById(accountId)
            .orElseThrow(() -> new AppException("ACCOUNT_NOT_FOUND", "Account not found", 404));
    SaasPlan plan =
        plans
            .findPlanById(planId)
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404));
    SaasSubscription current =
        subs.findByAccountId(accountId)
            .orElseGet(
                () -> {
                  ensureFreeSubscription(account.pharmacyId());
                  return requireSub(accountId);
                });
    Map<String, Object> before =
        Map.of(
            "override_plan_id",
            String.valueOf(current.overridePlanId()),
            "status",
            current.status());
    String status =
        SubscriptionStatus.EXPIRED.equals(current.status())
                || SubscriptionStatus.CANCELLED.equals(current.status())
            ? SubscriptionStatus.ACTIVE
            : current.status();
    SaasSubscription updated =
        new SaasSubscription(
            current.id(),
            current.accountId(),
            current.planId(),
            current.scheduledPlanId(),
            status,
            current.billingCycle(),
            current.renewalDate(),
            current.trialEndsAt(),
            current.autoRenew(),
            current.cancelledAt(),
            current.cancelsAt(),
            null,
            current.pastDueAt(),
            current.lastInvoiceId(),
            plan.id(),
            overrideExpiresAt,
            reason.trim(),
            current.createdAt(),
            now);
    subs.update(updated);
    syncDenormAndPharmacy(account.id(), account.pharmacyId(), plan.name(), status, now);
    Map<String, Object> after =
        Map.of(
            "override_plan_id",
            plan.id().toString(),
            "override_expires_at",
            overrideExpiresAt.toString(),
            "override_reason",
            reason.trim());
    audit.append(
        "saas_subscription",
        principal.subject(),
        principal.role().value(),
        current.id(),
        "saas_subscription.override",
        before,
        after);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("account_id", accountId);
    data.put("override_plan", plan.name());
    data.put("override_expires_at", overrideExpiresAt);
    data.put("override_by", principal.subject());
    data.put("override_at", now);
    return data;
  }

  /** Effective CRM plan name for module gates (override wins while active). */
  public String effectivePlanName(UUID pharmacyId) {
    Instant now = clock.instant();
    return plans
        .findAccountByPharmacyId(pharmacyId)
        .flatMap(a -> subs.findByAccountId(a.id()))
        .map(s -> effectivePlanName(s, now))
        .orElse(PlanNames.FREE);
  }

  public String effectivePlanName(SaasSubscription sub, Instant now) {
    if (sub.overrideActive(now)) {
      return plans.findPlanById(sub.overridePlanId()).map(SaasPlan::name).orElse(PlanNames.FREE);
    }
    if (SubscriptionStatus.EXPIRED.equals(sub.status())
        || SubscriptionStatus.CANCELLED.equals(sub.status())) {
      return PlanNames.FREE;
    }
    return plans.findPlanById(sub.planId()).map(SaasPlan::name).orElse(PlanNames.FREE);
  }

  @Transactional
  public void processScheduledJobs() {
    Instant now = clock.instant();
    processAutoRenewals(now);
    processPastDueExpirations(now);
    processTrialEnds(now);
    processCancelsDue(now);
    processOverridesExpired(now);
  }

  void processAutoRenewals(Instant now) {
    Instant windowEnd = now.plus(AUTO_RENEW_LEAD_DAYS, ChronoUnit.DAYS);
    for (SaasSubscription sub : subs.findDueForAutoRenew(now, windowEnd)) {
      if (sub.cancelsAt() != null) {
        continue;
      }
      SaasPlan plan = plans.findPlanById(sub.planId()).orElse(null);
      if (plan == null || PlanNames.FREE.equals(plan.name())) {
        continue;
      }
      Instant renewal = BillingCycle.advance(sub.renewalDate(), sub.billingCycle());
      UUID planId = sub.scheduledPlanId() != null ? sub.scheduledPlanId() : sub.planId();
      SaasPlan applied = plans.findPlanById(planId).orElse(plan);
      long planAmount =
          BillingCycle.cyclePricePaise(applied.priceMonthlyPaise(), sub.billingCycle());
      LocalDate periodFrom = LocalDate.ofInstant(sub.renewalDate(), ZoneOffset.UTC);
      LocalDate periodTo = LocalDate.ofInstant(renewal, ZoneOffset.UTC);
      List<InvoiceIssuingPort.LineDraft> lines =
          billingLines(sub.accountId(), applied, sub.billingCycle(), planAmount, 0L);
      long amount = sumPaise(lines);
      String chargeKey =
          "auto-renew:" + sub.id() + ":" + LocalDate.ofInstant(sub.renewalDate(), ZoneOffset.UTC);
      try {
        payments.charge(sub.accountId(), amount, chargeKey, chargeKey);
        UUID invoiceId =
            invoices.issue(
                sub.accountId(),
                sub.id(),
                applied.name(),
                periodFrom,
                periodTo,
                periodFrom,
                lines,
                InvoiceStatus.PAID,
                now,
                "UPI",
                chargeKey);
        SaasSubscription updated =
            new SaasSubscription(
                sub.id(),
                sub.accountId(),
                planId,
                null,
                SubscriptionStatus.ACTIVE,
                sub.billingCycle(),
                renewal,
                null,
                true,
                null,
                null,
                null,
                null,
                invoiceId,
                sub.overridePlanId(),
                sub.overrideExpiresAt(),
                sub.overrideReason(),
                sub.createdAt(),
                now);
        subs.update(updated);
        syncAccount(sub.accountId(), applied.name(), SubscriptionStatus.ACTIVE, now);
      } catch (AppException ex) {
        if (!"PAYMENT_FAILED".equals(ex.code())) {
          throw ex;
        }
        UUID invoiceId =
            invoices.issue(
                sub.accountId(),
                sub.id(),
                applied.name(),
                periodFrom,
                periodTo,
                periodFrom,
                lines,
                InvoiceStatus.DUE,
                null,
                null,
                null);
        SaasSubscription pastDue =
            new SaasSubscription(
                sub.id(),
                sub.accountId(),
                sub.planId(),
                sub.scheduledPlanId(),
                SubscriptionStatus.PAST_DUE,
                sub.billingCycle(),
                sub.renewalDate(),
                sub.trialEndsAt(),
                sub.autoRenew(),
                sub.cancelledAt(),
                sub.cancelsAt(),
                sub.expiresAt(),
                now,
                invoiceId,
                sub.overridePlanId(),
                sub.overrideExpiresAt(),
                sub.overrideReason(),
                sub.createdAt(),
                now);
        subs.update(pastDue);
        String planName =
            plans.findPlanById(sub.planId()).map(SaasPlan::name).orElse(PlanNames.FREE);
        syncAccount(sub.accountId(), planName, SubscriptionStatus.PAST_DUE, now);
        outbox.publish(
            "crm.subscription.dunning_started",
            sub.id(),
            Map.of(
                "subscription_id",
                sub.id().toString(),
                "account_id",
                sub.accountId().toString(),
                "invoice_id",
                invoiceId.toString()));
      }
    }
  }

  void processPastDueExpirations(Instant now) {
    Instant cutoff = now.minus(PAST_DUE_GRACE_DAYS, ChronoUnit.DAYS);
    for (SaasSubscription sub : subs.findPastDueExpired(cutoff)) {
      if (sub.overrideActive(now)) {
        continue;
      }
      SaasPlan free =
          plans
              .findPlanByName(PlanNames.FREE)
              .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "FREE plan missing", 404));
      SaasSubscription updated =
          new SaasSubscription(
              sub.id(),
              sub.accountId(),
              free.id(),
              null,
              SubscriptionStatus.EXPIRED,
              sub.billingCycle(),
              sub.renewalDate(),
              null,
              false,
              sub.cancelledAt(),
              sub.cancelsAt(),
              now,
              sub.pastDueAt(),
              sub.lastInvoiceId(),
              sub.overridePlanId(),
              sub.overrideExpiresAt(),
              sub.overrideReason(),
              sub.createdAt(),
              now);
      subs.update(updated);
      syncAccount(sub.accountId(), PlanNames.FREE, SubscriptionStatus.EXPIRED, now);
      outbox.publish(
          "crm.subscription.expired",
          sub.id(),
          Map.of("subscription_id", sub.id().toString(), "account_id", sub.accountId().toString()));
    }
  }

  void processTrialEnds(Instant now) {
    for (SaasSubscription sub : subs.findTrialsEnding(now)) {
      SaasPlan free =
          plans
              .findPlanByName(PlanNames.FREE)
              .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "FREE plan missing", 404));
      SaasSubscription updated =
          new SaasSubscription(
              sub.id(),
              sub.accountId(),
              free.id(),
              null,
              SubscriptionStatus.ACTIVE,
              BillingCycle.MONTHLY,
              BillingCycle.advance(now, BillingCycle.MONTHLY),
              null,
              true,
              null,
              null,
              null,
              null,
              sub.lastInvoiceId(),
              sub.overridePlanId(),
              sub.overrideExpiresAt(),
              sub.overrideReason(),
              sub.createdAt(),
              now);
      subs.update(updated);
      syncAccount(sub.accountId(), PlanNames.FREE, SubscriptionStatus.ACTIVE, now);
    }
  }

  void processCancelsDue(Instant now) {
    for (SaasSubscription sub : subs.findCancelsDue(now)) {
      SaasPlan free =
          plans
              .findPlanByName(PlanNames.FREE)
              .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "FREE plan missing", 404));
      SaasSubscription updated =
          new SaasSubscription(
              sub.id(),
              sub.accountId(),
              free.id(),
              null,
              SubscriptionStatus.CANCELLED,
              sub.billingCycle(),
              sub.renewalDate(),
              null,
              false,
              sub.cancelledAt(),
              sub.cancelsAt(),
              now,
              null,
              sub.lastInvoiceId(),
              sub.overridePlanId(),
              sub.overrideExpiresAt(),
              sub.overrideReason(),
              sub.createdAt(),
              now);
      subs.update(updated);
      syncAccount(sub.accountId(), PlanNames.FREE, SubscriptionStatus.CANCELLED, now);
    }
  }

  void processOverridesExpired(Instant now) {
    for (SaasSubscription sub : subs.findOverridesExpired(now)) {
      SaasSubscription cleared =
          new SaasSubscription(
              sub.id(),
              sub.accountId(),
              sub.planId(),
              sub.scheduledPlanId(),
              sub.status(),
              sub.billingCycle(),
              sub.renewalDate(),
              sub.trialEndsAt(),
              sub.autoRenew(),
              sub.cancelledAt(),
              sub.cancelsAt(),
              sub.expiresAt(),
              sub.pastDueAt(),
              sub.lastInvoiceId(),
              null,
              null,
              null,
              sub.createdAt(),
              now);
      subs.update(cleared);
      String planName = plans.findPlanById(sub.planId()).map(SaasPlan::name).orElse(PlanNames.FREE);
      syncAccount(sub.accountId(), planName, sub.status(), now);
    }
  }

  void markPastDue(SaasSubscription sub, Instant now) {
    SaasSubscription updated =
        new SaasSubscription(
            sub.id(),
            sub.accountId(),
            sub.planId(),
            sub.scheduledPlanId(),
            SubscriptionStatus.PAST_DUE,
            sub.billingCycle(),
            sub.renewalDate(),
            sub.trialEndsAt(),
            sub.autoRenew(),
            sub.cancelledAt(),
            sub.cancelsAt(),
            sub.expiresAt(),
            now,
            sub.lastInvoiceId(),
            sub.overridePlanId(),
            sub.overrideExpiresAt(),
            sub.overrideReason(),
            sub.createdAt(),
            now);
    subs.update(updated);
    String planName = plans.findPlanById(sub.planId()).map(SaasPlan::name).orElse(PlanNames.FREE);
    syncAccount(sub.accountId(), planName, SubscriptionStatus.PAST_DUE, now);
    outbox.publish(
        "crm.subscription.dunning_started",
        sub.id(),
        Map.of("subscription_id", sub.id().toString(), "account_id", sub.accountId().toString()));
  }

  /** Test helper: start a 14-day STARTER trial without payment. */
  @Transactional
  public SaasSubscription startStarterTrial(UUID pharmacyId) {
    Instant now = clock.instant();
    ensureFreeSubscription(pharmacyId);
    CrmAccount account = requireAccount(pharmacyId);
    SaasSubscription current = requireSub(account.id());
    SaasPlan starter =
        plans
            .findPlanByName(PlanNames.STARTER)
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "STARTER not found", 404));
    Instant trialEnds = now.plus(TRIAL_DAYS, ChronoUnit.DAYS);
    SaasSubscription updated =
        new SaasSubscription(
            current.id(),
            current.accountId(),
            starter.id(),
            null,
            SubscriptionStatus.TRIAL,
            BillingCycle.MONTHLY,
            trialEnds,
            trialEnds,
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            current.createdAt(),
            now);
    subs.update(updated);
    syncDenormAndPharmacy(
        account.id(), pharmacyId, PlanNames.STARTER, SubscriptionStatus.TRIAL, now);
    return updated;
  }

  static long[] prorateUpgrade(
      long oldMonthlyPaise, long newMonthlyPaise, String cycle, Instant now, Instant renewalDate) {
    long oldCycle = BillingCycle.cyclePricePaise(oldMonthlyPaise, cycle);
    long newCycle = BillingCycle.cyclePricePaise(newMonthlyPaise, cycle);
    long totalSeconds =
        Math.max(1, ChronoUnit.SECONDS.between(renewalDate.minus(cyclePeriod(cycle)), renewalDate));
    long remaining = Math.max(0, ChronoUnit.SECONDS.between(now, renewalDate));
    long credit = oldCycle * remaining / totalSeconds;
    long newPortion = newCycle * remaining / totalSeconds;
    long charge = Math.max(0, newPortion - credit);
    return new long[] {credit, charge};
  }

  private static Duration cyclePeriod(String cycle) {
    return BillingCycle.ANNUAL.equals(cycle) ? Duration.ofDays(365) : Duration.ofDays(30);
  }

  private void syncDenormAndPharmacy(
      UUID accountId, UUID pharmacyId, String planName, String status, Instant now) {
    subs.updateAccountDenorm(accountId, planName, status, now);
    planSync.syncPlan(pharmacyId, planName);
  }

  private void syncAccount(UUID accountId, String planName, String status, Instant now) {
    subs.updateAccountDenorm(accountId, planName, status, now);
    subs.findPharmacyId(accountId).ifPresent(pid -> planSync.syncPlan(pid, planName));
  }

  private SaasSubscription withPlan(
      SaasSubscription current,
      UUID planId,
      String status,
      String cycle,
      Instant renewal,
      Instant trialEnds,
      UUID invoiceId,
      Instant now) {
    return new SaasSubscription(
        current.id(),
        current.accountId(),
        planId,
        current.scheduledPlanId(),
        status,
        cycle,
        renewal,
        trialEnds,
        current.autoRenew(),
        current.cancelledAt(),
        current.cancelsAt(),
        current.expiresAt(),
        current.pastDueAt(),
        invoiceId,
        current.overridePlanId(),
        current.overrideExpiresAt(),
        current.overrideReason(),
        current.createdAt(),
        now);
  }

  private static void validateCoupon(String couponCode) {
    if (couponCode == null || couponCode.isBlank()) {
      return;
    }
    String c = couponCode.trim().toUpperCase();
    if ("SAAS20".equals(c) || "WELCOME".equals(c)) {
      return;
    }
    throw new AppException("INVALID_COUPON", "Coupon code is invalid or expired", 400);
  }

  private static String requireIdempotencyKey(String key) {
    if (key == null || key.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "Idempotency-Key is required", 400);
    }
    if (key.length() > 128) {
      throw new AppException("VALIDATION_ERROR", "Idempotency-Key max 128 characters", 400);
    }
    return key.trim();
  }

  private Optional<Map<String, Object>> replayCached(String key, UUID accountId, String operation) {
    Optional<SaasSubscriptionIdempotencyStore.CachedResponse> cached = idempotency.findByKey(key);
    if (cached.isEmpty()) {
      return Optional.empty();
    }
    SaasSubscriptionIdempotencyStore.CachedResponse row = cached.get();
    if (!accountId.equals(row.accountId()) || !operation.equals(row.operation())) {
      throw new AppException(
          "VALIDATION_ERROR", "Idempotency-Key already used for a different operation", 400);
    }
    try {
      return Optional.of(
          objectMapper.readValue(row.responseJson(), new TypeReference<Map<String, Object>>() {}));
    } catch (Exception ex) {
      throw new AppException("INTERNAL_ERROR", "Failed to replay cached response", 500);
    }
  }

  private void cacheResponse(
      String key, UUID accountId, String operation, Map<String, Object> data, Instant now) {
    try {
      idempotency.insert(key, accountId, operation, objectMapper.writeValueAsString(data), now);
    } catch (Exception ex) {
      throw new AppException("INTERNAL_ERROR", "Failed to persist idempotency response", 500);
    }
  }

  private CrmAccount requireAccount(UUID pharmacyId) {
    return plans
        .findAccountByPharmacyId(pharmacyId)
        .orElseThrow(() -> new AppException("ACCOUNT_NOT_FOUND", "Account not found", 404));
  }

  private SaasSubscription requireSub(UUID accountId) {
    return subs.findByAccountId(accountId)
        .orElseThrow(
            () -> new AppException("SUBSCRIPTION_NOT_FOUND", "Subscription not found", 404));
  }

  private static void requireOwner(MedmatePrincipal principal) {
    if (principal == null
        || principal.role() != AuthRole.PHARMACY_OWNER
        || principal.pharmacyId() == null) {
      throw new AppException("FORBIDDEN", "Pharmacy owner access required", 403);
    }
  }

  private static void requireMutable(SaasSubscription sub) {
    if (SubscriptionStatus.EXPIRED.equals(sub.status())
        || SubscriptionStatus.CANCELLED.equals(sub.status())) {
      throw new AppException("SUBSCRIPTION_NOT_ACTIVE", "Subscription is not active", 409);
    }
  }

  private List<InvoiceIssuingPort.LineDraft> billingLines(
      UUID accountId, SaasPlan plan, String cycle, long planAmountPaise, long creditPaise) {
    List<InvoiceIssuingPort.LineDraft> lines = new ArrayList<>();
    String cycleLabel = BillingCycle.ANNUAL.equals(cycle) ? "Annual" : "Monthly";
    lines.add(
        new InvoiceIssuingPort.LineDraft(
            plan.name() + " Plan - " + cycleLabel, planAmountPaise, InvoiceLineItemType.PLAN));
    for (SaasAddon addon : plans.listActiveAddons()) {
      if (plans.findActiveAccountAddon(accountId, addon.id()).isPresent()) {
        long addonAmt = BillingCycle.cyclePricePaise(addon.priceMonthlyPaise(), cycle);
        lines.add(
            new InvoiceIssuingPort.LineDraft(
                addon.name().replace('_', ' ') + " Add-on", addonAmt, InvoiceLineItemType.ADDON));
      }
    }
    if (creditPaise > 0) {
      lines.add(
          new InvoiceIssuingPort.LineDraft(
              "Prorated credit", -creditPaise, InvoiceLineItemType.CREDIT));
    }
    return lines;
  }

  private static long sumPaise(List<InvoiceIssuingPort.LineDraft> lines) {
    long sum = 0L;
    for (InvoiceIssuingPort.LineDraft line : lines) {
      sum = Math.addExact(sum, line.amountPaise());
    }
    return Math.max(0L, sum);
  }
}

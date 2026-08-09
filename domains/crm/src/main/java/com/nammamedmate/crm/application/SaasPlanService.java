package com.nammamedmate.crm.application;

import com.nammamedmate.crm.application.port.out.CrmAuditPort;
import com.nammamedmate.crm.application.port.out.CrmPlanLookupPort;
import com.nammamedmate.crm.application.port.out.SaasModuleUsageStore;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.domain.AccountModuleOverride;
import com.nammamedmate.crm.domain.CrmMoney;
import com.nammamedmate.crm.domain.ModuleMatrixRow;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.crm.domain.PlanSubscriber;
import com.nammamedmate.crm.domain.SaasAddon;
import com.nammamedmate.crm.domain.SaasPlan;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SaasPlanService implements CrmPlanLookupPort {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

  private final SaasPlanStore store;
  private final SaasModuleUsageStore moduleUsage;
  private final CrmAuditPort audit;
  private final Clock clock;
  private final SubscriptionService subscriptions;

  public SaasPlanService(
      SaasPlanStore store,
      SaasModuleUsageStore moduleUsage,
      CrmAuditPort audit,
      Clock clock,
      SubscriptionService subscriptions) {
    this.store = store;
    this.moduleUsage = moduleUsage;
    this.audit = audit;
    this.clock = clock;
    this.subscriptions = subscriptions;
  }

  @Override
  public Optional<String> planNameForPharmacy(UUID pharmacyId) {
    if (pharmacyId == null) {
      return Optional.empty();
    }
    return Optional.of(subscriptions.effectivePlanName(pharmacyId));
  }

  @Override
  public boolean planIncludesModule(String planName, String moduleId) {
    if (planName == null || moduleId == null) {
      return false;
    }
    return store.planIncludesModule(planName, moduleId);
  }

  @Override
  public boolean moduleAccessibleForPharmacy(UUID pharmacyId, String moduleId) {
    if (pharmacyId == null || moduleId == null) {
      return false;
    }
    return store
        .findAccountByPharmacyId(pharmacyId)
        .flatMap(a -> moduleUsage.findOverride(a.id(), moduleId))
        .map(AccountModuleOverride::enabled)
        .orElseGet(() -> planIncludesModule(subscriptions.effectivePlanName(pharmacyId), moduleId));
  }

  public Map<String, Object> listPlansAdmin(MedmatePrincipal principal) {
    requireAdminRead(principal);
    List<Map<String, Object>> plans = new ArrayList<>();
    for (SaasPlan plan : store.listActivePlans()) {
      long subscribers = store.countActiveSubscribers(plan.name());
      long mrrPaise = Math.multiplyExact(subscribers, plan.priceMonthlyPaise());
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("plan_id", plan.id());
      row.put("name", plan.name());
      row.put("price_monthly_rs", CrmMoney.paiseToRupees(plan.priceMonthlyPaise()));
      row.put(
          "price_annual_rs",
          CrmMoney.paiseToRupees(CrmMoney.annualPaise(plan.priceMonthlyPaise())));
      row.put("seat_limit", plan.seatLimit());
      row.put("invoice_cap_monthly", plan.invoiceCapMonthly());
      row.put("module_count", store.countModulesForPlan(plan.name()));
      row.put("subscriber_count", subscribers);
      row.put("mrr_rs", CrmMoney.paiseToRupees(mrrPaise));
      plans.add(row);
    }
    return Map.of("plans", plans);
  }

  public Map<String, Object> getPlanAdmin(
      MedmatePrincipal principal, UUID planId, Integer page, Integer limit) {
    requireAdminRead(principal);
    SaasPlan plan =
        store
            .findPlanById(planId)
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404));
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    long total = store.countActiveSubscribers(plan.name());
    List<PlanSubscriber> subs = store.listSubscribers(plan.name(), (p - 1) * lim, lim);
    List<Map<String, Object>> subRows = new ArrayList<>();
    for (PlanSubscriber s : subs) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("account_id", s.accountId());
      row.put("pharmacy_name", s.pharmacyName());
      row.put("since", DAY.format(LocalDate.ofInstant(s.since(), IST)));
      subRows.add(row);
    }
    Map<String, Object> pricing = new LinkedHashMap<>();
    pricing.put("monthly_rs", CrmMoney.paiseToRupees(plan.priceMonthlyPaise()));
    pricing.put(
        "annual_rs", CrmMoney.paiseToRupees(CrmMoney.annualPaise(plan.priceMonthlyPaise())));
    pricing.put("annual_savings_pct", CrmMoney.annualSavingsPct());

    Map<String, Object> limits = new LinkedHashMap<>();
    limits.put("seats", plan.seatLimit());
    limits.put("invoices_per_month", plan.invoiceCapMonthly());

    Map<String, Object> subscriberList = new LinkedHashMap<>();
    subscriberList.put("data", subRows);
    subscriberList.put(
        "meta",
        Map.of("page", p, "limit", lim, "total", total, "has_next", (long) p * lim < total));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("plan_id", plan.id());
    data.put("name", plan.name());
    data.put("pricing", pricing);
    data.put("limits", limits);
    data.put("included_modules", store.moduleCodesForPlan(plan.name()));
    data.put("upgrade_path", PlanNames.upgradePath(plan.name()));
    data.put("subscriber_count", total);
    data.put("subscriber_list", subscriberList);
    return data;
  }

  @Transactional
  public Map<String, Object> updatePlan(
      MedmatePrincipal principal,
      UUID planId,
      BigDecimal priceMonthlyRs,
      Integer seatLimit,
      Integer invoiceCapMonthly) {
    if (principal == null || principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Only admin_super may update plan pricing", 403);
    }
    SaasPlan before =
        store
            .findPlanById(planId)
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404));
    if (PlanNames.FREE.equals(before.name()) && priceMonthlyRs != null) {
      throw new AppException("CANNOT_MODIFY_FREE_PLAN_PRICE", "FREE plan price must remain 0", 422);
    }
    Long newPaise = priceMonthlyRs == null ? null : CrmMoney.rupeesToPaise(priceMonthlyRs);
    Instant now = clock.instant();
    SaasPlan after = store.updatePlan(planId, newPaise, seatLimit, invoiceCapMonthly, now);

    Map<String, Object> beforeState = planAuditState(before);
    Map<String, Object> afterState = planAuditState(after);
    audit.append(
        "saas_plan",
        principal.subject(),
        principal.role().value(),
        planId,
        "saas_plan.updated",
        beforeState,
        afterState);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("plan_id", after.id());
    data.put("updated_at", after.updatedAt());
    data.put("updated_by", principal.subject());
    return data;
  }

  public Map<String, Object> listAddons(MedmatePrincipal principal) {
    requireAdminRead(principal);
    long totalActive = store.countTotalActiveAccounts();
    List<Map<String, Object>> addons = new ArrayList<>();
    for (SaasAddon addon : store.listActiveAddons()) {
      long withAddon = store.countActiveAccountsWithAddon(addon.id());
      long mrrPaise = Math.multiplyExact(withAddon, addon.priceMonthlyPaise());
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("addon_id", addon.id());
      row.put("name", addon.name());
      row.put("price_monthly_rs", CrmMoney.paiseToRupees(addon.priceMonthlyPaise()));
      row.put("attach_rate_pct", CrmMoney.attachRatePct(withAddon, totalActive));
      row.put("mrr_rs", CrmMoney.paiseToRupees(mrrPaise));
      addons.add(row);
    }
    return Map.of("addons", addons);
  }

  @Transactional
  public Map<String, Object> attachAddon(MedmatePrincipal principal, UUID accountId, UUID addonId) {
    requireAdminWrite(principal);
    store
        .findAccountById(accountId)
        .orElseThrow(() -> new AppException("ACCOUNT_NOT_FOUND", "Account not found", 404));
    SaasAddon addon =
        store
            .findAddonById(addonId)
            .orElseThrow(() -> new AppException("ADDON_NOT_FOUND", "Add-on not found", 404));
    if (store.findActiveAccountAddon(accountId, addonId).isPresent()) {
      throw new AppException("ADDON_ALREADY_ATTACHED", "Add-on already active on account", 409);
    }
    Instant now = clock.instant();
    store.attachAddon(accountId, addonId, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("account_id", accountId);
    data.put("addon_id", addonId);
    data.put("effective_from", now);
    data.put("next_billing_amount_rs", CrmMoney.paiseToRupees(addon.priceMonthlyPaise()));
    data.put("message", "Add-on activated immediately. Billed on next invoice cycle.");
    return data;
  }

  @Transactional
  public Map<String, Object> detachAddon(MedmatePrincipal principal, UUID accountId, UUID addonId) {
    requireAdminWrite(principal);
    store
        .findAccountById(accountId)
        .orElseThrow(() -> new AppException("ACCOUNT_NOT_FOUND", "Account not found", 404));
    SaasAddon addon =
        store
            .findAddonById(addonId)
            .orElseThrow(() -> new AppException("ADDON_NOT_FOUND", "Add-on not found", 404));
    store
        .findActiveAccountAddon(accountId, addonId)
        .orElseThrow(() -> new AppException("ADDON_NOT_ATTACHED", "Add-on is not attached", 404));
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, IST);
    long creditPaise =
        CrmMoney.proratedCreditPaise(
            addon.priceMonthlyPaise(), today.getDayOfMonth(), today.lengthOfMonth());
    store.detachAddon(accountId, addonId, now);
    BigDecimal creditRs = CrmMoney.paiseToRupees(creditPaise);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("account_id", accountId);
    data.put("addon_id", addonId);
    data.put("detached_at", now);
    data.put("prorated_credit_rs", creditRs);
    data.put(
        "message",
        "Add-on detached. Prorated credit of Rs "
            + creditRs.toPlainString()
            + " applied to next invoice.");
    return data;
  }

  public Map<String, Object> moduleMatrix(MedmatePrincipal principal) {
    requireAdminRead(principal);
    List<Map<String, Object>> modules = new ArrayList<>();
    for (ModuleMatrixRow row : store.listModuleMatrix()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("module_id", row.moduleId());
      m.put("module_name", row.moduleName());
      m.put("group", row.groupName());
      m.put("available_on", row.planNames());
      modules.add(m);
    }
    return Map.of("modules", modules);
  }

  public Map<String, Object> listPlansForPharmacy(MedmatePrincipal principal) {
    if (principal == null
        || principal.role() != AuthRole.PHARMACY_OWNER
        || principal.pharmacyId() == null) {
      throw new AppException("FORBIDDEN", "Pharmacy owner access required", 403);
    }
    String current = subscriptions.effectivePlanName(principal.pharmacyId());
    int currentTier = PlanNames.tierIndex(current);
    List<Map<String, Object>> plans = new ArrayList<>();
    for (SaasPlan plan : store.listActivePlans()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("plan_id", plan.id());
      row.put("name", plan.name());
      row.put("price_monthly_rs", CrmMoney.paiseToRupees(plan.priceMonthlyPaise()));
      row.put(
          "price_annual_rs",
          CrmMoney.paiseToRupees(CrmMoney.annualPaise(plan.priceMonthlyPaise())));
      row.put("seat_limit", plan.seatLimit());
      row.put("included_modules", store.moduleCodesForPlan(plan.name()));
      boolean isCurrent = plan.name().equals(current);
      row.put("is_current", isCurrent);
      if (!isCurrent && PlanNames.tierIndex(plan.name()) > currentTier) {
        row.put("upgrade_cta", "Upgrade Now");
      }
      plans.add(row);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("current_plan", current);
    data.put("plans", plans);
    return data;
  }

  private static Map<String, Object> planAuditState(SaasPlan plan) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", plan.name());
    m.put("price_monthly_rs", CrmMoney.paiseToRupees(plan.priceMonthlyPaise()));
    m.put("seat_limit", plan.seatLimit());
    m.put("invoice_cap_monthly", plan.invoiceCapMonthly());
    return m;
  }

  private static void requireAdminRead(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Admin access required", 403);
    }
    AuthRole r = principal.role();
    if (r != AuthRole.ADMIN_SUPER
        && r != AuthRole.ADMIN_FINANCE
        && r != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Admin access required", 403);
    }
  }

  private static void requireAdminWrite(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Admin access required", 403);
    }
    AuthRole r = principal.role();
    if (r != AuthRole.ADMIN_SUPER && r != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Admin operations access required", 403);
    }
  }
}

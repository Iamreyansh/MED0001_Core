package com.nammamedmate.crm.application;

import com.nammamedmate.crm.application.port.out.CrmModuleNudgeOutboxPort;
import com.nammamedmate.crm.application.port.out.ModuleUsageMeterPort;
import com.nammamedmate.crm.application.port.out.SaasModuleUsageStore;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.domain.AccountModuleOverride;
import com.nammamedmate.crm.domain.AdoptionMath;
import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.ModuleMatrixRow;
import com.nammamedmate.crm.domain.ModuleUsageMonthly;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.crm.domain.SaasPlan;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeatureAdoptionService implements ModuleUsageMeterPort {

  private static final Logger log = LoggerFactory.getLogger(FeatureAdoptionService.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final SaasModuleUsageStore usage;
  private final SaasPlanStore plans;
  private final CrmModuleNudgeOutboxPort nudgeOutbox;
  private final Clock clock;

  public FeatureAdoptionService(
      SaasModuleUsageStore usage,
      SaasPlanStore plans,
      CrmModuleNudgeOutboxPort nudgeOutbox,
      Clock clock) {
    this.usage = usage;
    this.plans = plans;
    this.nudgeOutbox = nudgeOutbox;
    this.clock = clock;
  }

  public Map<String, Object> listModules(
      MedmatePrincipal principal, String group, String tier, String sort, String order) {
    requireAdminRead(principal);
    LocalDate month = currentMonth();
    List<Map<String, Object>> modules = new ArrayList<>();
    double adoptionSum = 0.0;
    int lowCount = 0;
    String topModule = null;
    double topPct = -1.0;

    for (ModuleMatrixRow row : usage.listModuleMatrix()) {
      if (group != null && !group.isBlank() && !row.groupName().equalsIgnoreCase(group.trim())) {
        continue;
      }
      String moduleTier = lowestTier(row.planNames());
      if (tier != null && !tier.isBlank() && !moduleTier.equalsIgnoreCase(tier.trim())) {
        continue;
      }
      long eligible = usage.countEligibleAccounts(row.moduleId());
      long using = usage.countAccountsUsing(row.moduleId(), month);
      double pct = AdoptionMath.adoptionPct(using, eligible);
      boolean low = AdoptionMath.isLowAdoption(pct);
      if (low) {
        lowCount++;
      }
      if (pct > topPct) {
        topPct = pct;
        topModule = row.moduleCode();
      }
      adoptionSum += pct;
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("module_id", row.moduleId());
      m.put("module_name", row.moduleName());
      m.put("group", row.groupName());
      m.put("tier", moduleTier);
      m.put("accounts_using", using);
      m.put("accounts_eligible", eligible);
      m.put("adoption_pct", pct);
      m.put("low_adoption", low);
      modules.add(m);
    }

    sortModules(modules, sort, order);

    Map<String, Object> chips = new LinkedHashMap<>();
    chips.put("module_count", modules.size());
    chips.put(
        "avg_adoption_pct",
        modules.isEmpty() ? 0.0 : Math.round(adoptionSum * 10.0 / modules.size()) / 10.0);
    chips.put("top_module", topModule);
    chips.put("low_adoption_count", lowCount);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("chips", chips);
    data.put("modules", modules);
    return data;
  }

  public Map<String, Object> getModule(MedmatePrincipal principal, String moduleId) {
    requireAdminWrite(principal);
    ModuleMatrixRow row =
        usage
            .findModuleById(moduleId)
            .orElseThrow(() -> new AppException("MODULE_NOT_FOUND", "Module not found", 404));
    LocalDate month = currentMonth();
    long eligible = usage.countEligibleAccounts(moduleId);
    long using = usage.countAccountsUsing(moduleId, month);
    double pct = AdoptionMath.adoptionPct(using, eligible);

    List<Map<String, Object>> notUsing = new ArrayList<>();
    for (SaasModuleUsageStore.EligibleAccountRow r : usage.listEligibleNotUsing(moduleId, month)) {
      Map<String, Object> rowMap = new LinkedHashMap<>();
      rowMap.put("account_id", r.accountId());
      rowMap.put("pharmacy_name", r.pharmacyName());
      rowMap.put("module_enabled", r.moduleEnabled());
      rowMap.put("event_count_this_month", r.eventCountThisMonth());
      rowMap.put("last_active_at", r.lastActiveAt());
      notUsing.add(rowMap);
    }

    List<Map<String, Object>> perAccount = new ArrayList<>();
    for (SaasModuleUsageStore.AccountUsageRow r : usage.listPerAccountUsage(moduleId, month)) {
      Map<String, Object> rowMap = new LinkedHashMap<>();
      rowMap.put("account_id", r.accountId());
      rowMap.put("pharmacy_name", r.pharmacyName());
      rowMap.put("event_count_this_month", r.eventCountThisMonth());
      rowMap.put("last_active_at", r.lastActiveAt());
      perAccount.add(rowMap);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("module_id", row.moduleId());
    data.put("module_name", row.moduleName());
    data.put("group", row.groupName());
    data.put("tier", lowestTier(row.planNames()));
    data.put("accounts_eligible", eligible);
    data.put("accounts_using", using);
    data.put("adoption_pct", pct);
    data.put("eligible_not_using", notUsing);
    data.put("per_account_event_counts", perAccount);
    return data;
  }

  @Transactional
  public Map<String, Object> toggleModule(
      MedmatePrincipal principal, UUID accountId, String moduleId, Boolean enabled, String reason) {
    requireAdminWrite(principal);
    if (enabled == null) {
      throw new AppException("VALIDATION_ERROR", "enabled is required", 422);
    }
    if (reason == null || reason.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "reason is required", 422);
    }
    usage
        .findModuleById(moduleId)
        .orElseThrow(() -> new AppException("MODULE_NOT_FOUND", "Module not found", 404));
    plans
        .findAccountById(accountId)
        .orElseThrow(() -> new AppException("ACCOUNT_NOT_FOUND", "Account not found", 404));

    Instant now = clock.instant();
    AccountModuleOverride ov =
        usage.upsertOverride(accountId, moduleId, enabled, reason.trim(), principal.subject(), now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("account_id", accountId);
    data.put("module_id", moduleId);
    data.put("enabled", ov.enabled());
    data.put("override", true);
    data.put("reason", ov.reason());
    data.put("toggled_by", ov.toggledBy());
    data.put("toggled_at", ov.toggledAt());
    return data;
  }

  public Map<String, Object> usageSummary(MedmatePrincipal principal, UUID accountId) {
    requireAdminWrite(principal);
    CrmAccount account =
        plans
            .findAccountById(accountId)
            .orElseThrow(() -> new AppException("ACCOUNT_NOT_FOUND", "Account not found", 404));
    SaasPlan plan =
        plans
            .findPlanByName(account.currentPlanName())
            .orElseGet(
                () ->
                    plans
                        .findPlanByName(PlanNames.FREE)
                        .orElseThrow(
                            () -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404)));

    Instant now = clock.instant();
    LocalDate month = LocalDate.ofInstant(now, IST).withDayOfMonth(1);
    Instant monthStart = month.atStartOfDay(IST).toInstant();
    Instant monthEnd = month.plusMonths(1).atStartOfDay(IST).toInstant();
    Instant since30d = now.minus(30, ChronoUnit.DAYS);

    List<String> eligibleCodes = plans.moduleCodesForPlan(plan.name());
    int modulesEligible = eligibleCodes.size();
    int modulesUsed = usage.countModulesUsedSince(accountId, since30d);
    int score = AdoptionMath.adoptionScore(modulesUsed, modulesEligible);

    Map<String, ModuleUsageMonthly> byModule = new LinkedHashMap<>();
    for (ModuleUsageMonthly u : usage.listAccountUsageMonth(accountId, month)) {
      byModule.put(u.moduleId(), u);
    }

    List<Map<String, Object>> eventCounts = new ArrayList<>();
    for (ModuleMatrixRow row : usage.listModuleMatrix()) {
      if (!eligibleCodes.contains(row.moduleCode())
          && usage
              .findOverride(accountId, row.moduleId())
              .filter(AccountModuleOverride::enabled)
              .isEmpty()) {
        continue;
      }
      ModuleUsageMonthly u = byModule.get(row.moduleId());
      Map<String, Object> rowMap = new LinkedHashMap<>();
      rowMap.put("module", row.moduleCode());
      rowMap.put("events", u == null ? 0 : u.eventCount());
      rowMap.put("last_active_at", u == null ? null : u.lastActiveAt());
      eventCounts.add(rowMap);
    }

    long seatsUsed = usage.countActiveStaff(account.pharmacyId());
    long invoicesUsed = usage.countInvoicesThisMonth(account.pharmacyId(), monthStart, monthEnd);

    Map<String, Object> seatUsage = new LinkedHashMap<>();
    seatUsage.put("used", seatsUsed);
    seatUsage.put("limit", plan.seatLimit());

    Map<String, Object> invoiceUsage = new LinkedHashMap<>();
    invoiceUsage.put("used", invoicesUsed);
    invoiceUsage.put("limit", plan.invoiceCapMonthly());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("account_id", accountId);
    data.put("pharmacy_name", usage.pharmacyName(account.pharmacyId()));
    data.put("seat_usage", seatUsage);
    data.put("invoice_usage_this_month", invoiceUsage);
    data.put("active_users", usage.listActiveStaffNames(account.pharmacyId()));
    data.put("last_active_at", usage.maxLastActive(accountId));
    data.put("adoption_score", score);
    data.put("module_event_counts", eventCounts);
    return data;
  }

  @Transactional
  public Map<String, Object> nudgeIneligible(
      MedmatePrincipal principal, String moduleId, String channel) {
    requireAdminWrite(principal);
    ModuleMatrixRow row =
        usage
            .findModuleById(moduleId)
            .orElseThrow(() -> new AppException("MODULE_NOT_FOUND", "Module not found", 404));
    String ch =
        channel == null || channel.isBlank() ? "EMAIL" : channel.trim().toUpperCase(Locale.ROOT);
    Instant now = clock.instant();
    Instant since30d = now.minus(30, ChronoUnit.DAYS);
    List<UUID> targets = usage.listNudgeTargetAccountIds(moduleId, since30d);
    if (targets.isEmpty()) {
      throw new AppException("NO_ELIGIBLE_ACCOUNTS", "No accounts qualify for nudge", 422);
    }

    UUID campaignId = Ids.newId();
    nudgeOutbox.publish(
        "crm.module.nudge",
        campaignId,
        Map.of(
            "module_id", moduleId,
            "channel", ch,
            "account_ids", targets,
            "sent_at", now.toString()));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("module_id", moduleId);
    data.put("module_name", row.moduleName());
    data.put("eligible_not_using_count", targets.size());
    data.put("nudge_sent_count", targets.size());
    data.put("channel", ch);
    data.put("sent_at", now);
    return data;
  }

  @Override
  public void recordUsage(UUID pharmacyId, String moduleId) {
    if (pharmacyId == null || moduleId == null) {
      return;
    }
    try {
      Optional<CrmAccount> account = plans.findAccountByPharmacyId(pharmacyId);
      if (account.isEmpty()) {
        return;
      }
      Instant now = clock.instant();
      LocalDate month = LocalDate.ofInstant(now, IST).withDayOfMonth(1);
      usage.incrementUsage(account.get().id(), moduleId, month, now);
    } catch (RuntimeException ex) {
      log.warn(
          "module usage metering failed pharmacy={} module={}: {}",
          pharmacyId,
          moduleId,
          ex.toString());
    }
  }

  private LocalDate currentMonth() {
    return LocalDate.ofInstant(clock.instant(), IST).withDayOfMonth(1);
  }

  static String lowestTier(List<String> planNames) {
    if (planNames == null || planNames.isEmpty()) {
      return PlanNames.FREE;
    }
    String best = null;
    int bestIdx = Integer.MAX_VALUE;
    for (String name : planNames) {
      int idx = PlanNames.tierIndex(name);
      if (idx >= 0 && idx < bestIdx) {
        bestIdx = idx;
        best = name;
      }
    }
    return best == null ? PlanNames.FREE : best;
  }

  @SuppressWarnings("unchecked")
  private static void sortModules(List<Map<String, Object>> modules, String sort, String order) {
    String key = sort == null || sort.isBlank() ? "adoption_pct" : sort.trim();
    boolean asc = "asc".equalsIgnoreCase(order);
    Comparator<Map<String, Object>> cmp =
        switch (key) {
          case "accounts_using" ->
              Comparator.comparingLong(m -> ((Number) m.get("accounts_using")).longValue());
          case "module" -> Comparator.comparing(m -> String.valueOf(m.get("module_name")));
          default ->
              Comparator.comparingDouble(m -> ((Number) m.get("adoption_pct")).doubleValue());
        };
    if (!asc) {
      cmp = cmp.reversed();
    }
    modules.sort(cmp);
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

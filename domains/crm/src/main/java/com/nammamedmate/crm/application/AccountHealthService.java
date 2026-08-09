package com.nammamedmate.crm.application;

import com.nammamedmate.crm.application.port.out.BusinessPerformancePort;
import com.nammamedmate.crm.application.port.out.CrmHealthOutboxPort;
import com.nammamedmate.crm.application.port.out.SaasAccountHealthStore;
import com.nammamedmate.crm.application.port.out.SaasInvoiceStore;
import com.nammamedmate.crm.application.port.out.SaasModuleUsageStore;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.application.port.out.SupportSatisfactionPort;
import com.nammamedmate.crm.domain.AccountHealthScore;
import com.nammamedmate.crm.domain.AccountHealthSnapshot;
import com.nammamedmate.crm.domain.AdoptionMath;
import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.CrmMoney;
import com.nammamedmate.crm.domain.HealthBand;
import com.nammamedmate.crm.domain.HealthMath;
import com.nammamedmate.crm.domain.ModuleMatrixRow;
import com.nammamedmate.crm.domain.ModuleUsageMonthly;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.crm.domain.SaasPlan;
import com.nammamedmate.crm.domain.SavePlay;
import com.nammamedmate.crm.domain.SavePlayActionType;
import com.nammamedmate.kernel.api.PaginationMeta;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountHealthService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  static final String SAVE_PLAY_EVENT = "crm.account.save_play_needed";

  private final SaasAccountHealthStore health;
  private final SaasPlanStore plans;
  private final SaasModuleUsageStore usage;
  private final SaasInvoiceStore invoices;
  private final SupportSatisfactionPort support;
  private final BusinessPerformancePort business;
  private final CrmHealthOutboxPort outbox;
  private final Clock clock;

  public AccountHealthService(
      SaasAccountHealthStore health,
      SaasPlanStore plans,
      SaasModuleUsageStore usage,
      SaasInvoiceStore invoices,
      SupportSatisfactionPort support,
      BusinessPerformancePort business,
      CrmHealthOutboxPort outbox,
      Clock clock) {
    this.health = health;
    this.plans = plans;
    this.usage = usage;
    this.invoices = invoices;
    this.support = support;
    this.business = business;
    this.outbox = outbox;
    this.clock = clock;
  }

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {
    public PagedResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  @Transactional
  public Map<String, Object> getHealth(MedmatePrincipal principal, UUID accountId) {
    requireView(principal);
    CrmAccount account = requireAccount(accountId);
    AccountHealthScore score =
        health.findByAccountId(accountId).orElseGet(() -> recomputeOne(account));
    return toHealthResponse(account, score);
  }

  public PagedResult listAtRisk(
      MedmatePrincipal principal, String healthBand, Integer page, Integer limit) {
    requireView(principal);
    String band = HealthBand.requireFilterBand(healthBand);
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    int offset = (p - 1) * lim;
    List<SaasAccountHealthStore.AtRiskRow> rows = health.listAtRisk(band, offset, lim);
    long total = health.countAtRisk(band);
    long mrrPaise = health.sumMrrAtRiskPaise();

    List<Map<String, Object>> accounts = new ArrayList<>();
    for (SaasAccountHealthStore.AtRiskRow r : rows) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("account_id", r.accountId());
      row.put("pharmacy_name", r.pharmacyName());
      row.put("plan", r.plan());
      row.put("mrr_rs", CrmMoney.paiseToRupees(r.mrrPaise()));
      row.put("overall_score", r.overallScore());
      row.put("health_band", r.healthBand());
      row.put("renewal_date", r.renewalDate());
      row.put("last_save_play_at", r.lastSavePlayAt());
      row.put("assigned_csm", r.assignedCsm());
      accounts.add(row);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("total_mrr_at_risk_rs", CrmMoney.paiseToRupees(mrrPaise));
    data.put("accounts", accounts);
    return new PagedResult(data, PaginationMeta.of(p, lim, total));
  }

  @Transactional
  public Map<String, Object> logSavePlay(
      MedmatePrincipal principal, UUID accountId, String actionType, String outcome, String notes) {
    requireWrite(principal);
    requireAccount(accountId);
    String type = SavePlayActionType.requireValid(actionType);
    if (outcome == null || outcome.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "outcome is required", 422);
    }
    Instant now = clock.instant();
    SavePlay play =
        health.insertSavePlay(
            new SavePlay(
                Ids.newId(),
                accountId,
                type,
                outcome.trim(),
                notes == null || notes.isBlank() ? null : notes.trim(),
                principal.subject(),
                now));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("save_play_id", play.id());
    data.put("account_id", play.accountId());
    data.put("action_type", play.actionType());
    data.put("logged_by", play.loggedBy());
    data.put("logged_at", play.createdAt());
    return data;
  }

  public Map<String, Object> getUsage(MedmatePrincipal principal, UUID accountId) {
    requireUsage(principal);
    CrmAccount account = requireAccount(accountId);
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, IST);
    Instant since = now.minus(30, ChronoUnit.DAYS);

    SaasPlan plan =
        plans
            .findPlanByName(account.currentPlanName())
            .orElseGet(
                () ->
                    plans
                        .findPlanByName(PlanNames.FREE)
                        .orElseThrow(
                            () -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404)));
    List<String> eligibleCodes = plans.moduleCodesForPlan(plan.name());

    LocalDate thisMonth = today.withDayOfMonth(1);
    LocalDate priorMonth = thisMonth.minusMonths(1);
    Map<String, ModuleUsageMonthly> byModule = new LinkedHashMap<>();
    for (ModuleUsageMonthly u : usage.listAccountUsageMonth(accountId, thisMonth)) {
      byModule.put(u.moduleId(), u);
    }
    for (ModuleUsageMonthly u : usage.listAccountUsageMonth(accountId, priorMonth)) {
      byModule.putIfAbsent(u.moduleId(), u);
    }

    Instant overallLast = null;
    List<Map<String, Object>> modules = new ArrayList<>();
    for (ModuleMatrixRow row : usage.listModuleMatrix()) {
      if (!eligibleCodes.contains(row.moduleCode())) {
        continue;
      }
      ModuleUsageMonthly u = byModule.get(row.moduleId());
      List<Map<String, Object>> eventsPerDay = new ArrayList<>();
      for (int i = 29; i >= 0; i--) {
        LocalDate d = today.minusDays(i);
        int count = 0;
        if (u != null
            && u.eventCount() > 0
            && u.lastActiveAt() != null
            && !u.lastActiveAt().isBefore(since)
            && LocalDate.ofInstant(u.lastActiveAt(), IST).equals(d)) {
          count = u.eventCount();
        }
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", d.toString());
        day.put("count", count);
        eventsPerDay.add(day);
      }
      Instant lastActive = u == null ? null : u.lastActiveAt();
      if (lastActive != null && (overallLast == null || lastActive.isAfter(overallLast))) {
        overallLast = lastActive;
      }
      Map<String, Object> mod = new LinkedHashMap<>();
      mod.put("module", row.moduleCode());
      mod.put("events_per_day", eventsPerDay);
      mod.put("last_active_at", lastActive);
      modules.add(mod);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("account_id", accountId);
    data.put("modules", modules);
    data.put("overall_last_active_at", overallLast);
    return data;
  }

  public Map<String, Object> healthKpis(MedmatePrincipal principal) {
    requireView(principal);
    SaasAccountHealthStore.HealthKpis k = health.kpis();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("avg_health_score", k.avgHealthScore());
    data.put("healthy_pct", k.healthyPct());
    data.put("moderate_pct", k.moderatePct());
    data.put("at_risk_count", k.atRiskCount());
    data.put("churning_count", k.churningCount());
    data.put("mrr_at_risk_rs", CrmMoney.paiseToRupees(k.mrrAtRiskPaise()));
    data.put("accounts_with_open_save_plays", k.accountsWithOpenSavePlays());
    data.put("computed_at", k.computedAt());
    return data;
  }

  @Transactional
  public void recomputeAll() {
    for (CrmAccount account : plans.listActiveAccounts()) {
      recomputeOne(account);
    }
  }

  AccountHealthScore recomputeOne(CrmAccount account) {
    Instant now = clock.instant();
    LocalDate scoreDate = LocalDate.ofInstant(now, IST);
    Instant since30d = now.minus(30, ChronoUnit.DAYS);

    SaasPlan plan =
        plans
            .findPlanByName(account.currentPlanName())
            .orElseGet(
                () ->
                    plans
                        .findPlanByName(PlanNames.FREE)
                        .orElseThrow(
                            () -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404)));
    List<String> eligibleCodes = plans.moduleCodesForPlan(plan.name());
    int modulesEligible = eligibleCodes.size();
    int modulesUsed = usage.countModulesUsedSince(account.id(), since30d);
    double productUsage = AdoptionMath.adoptionScore(modulesUsed, modulesEligible);

    double billing = HealthMath.billingHealth(invoices.listOpenStatuses(account.id()));
    double supportScore = support.scoreForAccount(account.id());
    double businessScore = business.scoreForAccount(account.id(), account.pharmacyId());
    double overall = HealthMath.overall(productUsage, billing, supportScore, businessScore);
    String band = HealthBand.fromScore(overall);
    List<String> risks =
        HealthMath.riskFactors(
            productUsage, billing, supportScore, businessScore, modulesUsed, modulesEligible);
    List<String> actions =
        HealthMath.recommendedActions(productUsage, billing, supportScore, businessScore);

    Optional<AccountHealthScore> previous = health.findByAccountId(account.id());
    Double prevOverall = previous.map(AccountHealthScore::overallScore).orElse(null);

    AccountHealthScore score =
        new AccountHealthScore(
            previous.map(AccountHealthScore::id).orElseGet(Ids::newId),
            account.id(),
            overall,
            productUsage,
            billing,
            supportScore,
            businessScore,
            band,
            risks,
            actions,
            now);
    health.upsert(score);
    health.upsertSnapshot(
        new AccountHealthSnapshot(
            Ids.newId(),
            account.id(),
            scoreDate,
            overall,
            band,
            productUsage,
            billing,
            supportScore,
            businessScore));

    if (HealthMath.shouldTriggerSavePlay(prevOverall, overall)) {
      outbox.publish(
          SAVE_PLAY_EVENT,
          account.id(),
          Map.of("account_id", account.id(), "overall_score", overall));
    }
    return score;
  }

  private Map<String, Object> toHealthResponse(CrmAccount account, AccountHealthScore score) {
    Map<String, Object> components = new LinkedHashMap<>();
    components.put("product_usage", score.productUsageScore());
    components.put("billing_health", score.billingHealthScore());
    components.put("support_satisfaction", score.supportSatisfactionScore());
    components.put("business_performance", score.businessPerformanceScore());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("account_id", account.id());
    data.put("pharmacy_name", usage.pharmacyName(account.pharmacyId()));
    data.put("overall_score", score.overallScore());
    data.put("health_band", score.healthBand());
    data.put("components", components);
    data.put("risk_factors", score.riskFactors());
    data.put("recommended_actions", score.recommendedActions());
    data.put("computed_at", score.computedAt());
    return data;
  }

  private CrmAccount requireAccount(UUID accountId) {
    return plans
        .findAccountById(accountId)
        .orElseThrow(() -> new AppException("ACCOUNT_NOT_FOUND", "Account not found", 404));
  }

  private static void requireView(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Admin access required", 403);
    }
    AuthRole r = principal.role();
    if (r != AuthRole.ADMIN_SUPER
        && r != AuthRole.ADMIN_OPERATIONS
        && r != AuthRole.ADMIN_FINANCE) {
      throw new AppException("FORBIDDEN", "Admin access required", 403);
    }
  }

  private static void requireWrite(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Admin access required", 403);
    }
    AuthRole r = principal.role();
    if (r != AuthRole.ADMIN_SUPER && r != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Admin operations access required", 403);
    }
  }

  private static void requireUsage(MedmatePrincipal principal) {
    requireWrite(principal);
  }
}

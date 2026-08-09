package com.nammamedmate.crm.application;

import com.nammamedmate.crm.application.port.out.CrmSubscriptionOutboxPort;
import com.nammamedmate.crm.application.port.out.InvoiceIssuingPort;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.application.port.out.SaasRenewalChurnStore;
import com.nammamedmate.crm.application.port.out.SaasSubscriptionStore;
import com.nammamedmate.crm.application.port.out.SubscriptionPaymentPort;
import com.nammamedmate.crm.domain.BillingCycle;
import com.nammamedmate.crm.domain.ChurnMath;
import com.nammamedmate.crm.domain.ChurnReason;
import com.nammamedmate.crm.domain.ChurnSurvey;
import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.CrmMoney;
import com.nammamedmate.crm.domain.InvoiceLineItemType;
import com.nammamedmate.crm.domain.InvoiceStatus;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.crm.domain.RenewalRiskLevel;
import com.nammamedmate.crm.domain.SaasAddon;
import com.nammamedmate.crm.domain.SaasGst;
import com.nammamedmate.crm.domain.SaasPlan;
import com.nammamedmate.crm.domain.SaasSubscription;
import com.nammamedmate.crm.domain.SubscriptionStatus;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RenewalChurnService {

  private static final Logger log = LoggerFactory.getLogger(RenewalChurnService.class);

  static final String WINBACK_EVENT = "crm.subscription.winback";
  static final String AT_RISK_CSM_EVENT = "crm.renewal.at_risk_csm";
  static final String MONTHLY_REPORT_EVENT = "crm.churn.monthly_report";
  static final int MANUAL_RENEW_WINDOW_DAYS = 7;
  static final int DEFAULT_RENEWAL_DAYS = 30;
  static final int MAX_RENEWAL_DAYS = 90;

  private final SaasRenewalChurnStore store;
  private final SaasPlanStore plans;
  private final SaasSubscriptionStore subs;
  private final SubscriptionPaymentPort payments;
  private final InvoiceIssuingPort invoices;
  private final CrmSubscriptionOutboxPort outbox;
  private final Clock clock;

  public RenewalChurnService(
      SaasRenewalChurnStore store,
      SaasPlanStore plans,
      SaasSubscriptionStore subs,
      SubscriptionPaymentPort payments,
      InvoiceIssuingPort invoices,
      CrmSubscriptionOutboxPort outbox,
      Clock clock) {
    this.store = store;
    this.plans = plans;
    this.subs = subs;
    this.payments = payments;
    this.invoices = invoices;
    this.outbox = outbox;
    this.clock = clock;
  }

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {
    public PagedResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  public Map<String, Object> dashboard(MedmatePrincipal principal) {
    requireView(principal);
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    LocalDate monthStart = today.withDayOfMonth(1);
    Instant periodStart = monthStart.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant periodEnd = monthStart.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant windowEnd = now.plus(DEFAULT_RENEWAL_DAYS, ChronoUnit.DAYS);
    Instant savePlaySince = now.minus(7, ChronoUnit.DAYS);

    long renewing = store.countRenewing(now, windowEnd);
    long mrrAtRisk = store.sumMrrAtRiskPaise(now, windowEnd);
    long churned = store.countChurnedLogos(periodStart, periodEnd);
    long startLogos = store.countStartOfPeriodLogos(periodStart, periodEnd);
    long mrrChurned = store.sumMrrChurnedPaise(periodStart, periodEnd);
    long savePlays = store.countSavePlaysSince(savePlaySince);

    Map<String, Object> chips = new LinkedHashMap<>();
    chips.put("renewing_in_30d", renewing);
    chips.put("mrr_at_risk_rs", CrmMoney.paiseToRupees(mrrAtRisk));
    chips.put("churned_logos_this_month", churned);
    chips.put("logo_churn_pct", ChurnMath.logoChurnPct(churned, startLogos));
    chips.put("mrr_churned_this_month_rs", CrmMoney.paiseToRupees(mrrChurned));

    Map<String, Object> banner = new LinkedHashMap<>();
    banner.put("active_save_plays", savePlays);
    banner.put(
        "message",
        savePlays + " active save plays in progress - track outcomes in account health.");

    List<Map<String, Object>> reasons = reasonChart(store.churnReasons(periodStart, periodEnd));
    List<Map<String, Object>> upcoming = new ArrayList<>();
    for (SaasRenewalChurnStore.UpcomingRow r :
        store.listUpcoming(now, windowEnd, null, null, 0, 50)) {
      upcoming.add(toUpcomingMap(r, today, false));
    }
    List<Map<String, Object>> churnLog = new ArrayList<>();
    for (SaasRenewalChurnStore.ChurnLogRow r : store.churnLog(periodStart, periodEnd, 50)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("account_id", r.accountId());
      row.put("pharmacy_name", r.pharmacyName());
      row.put("plan", r.plan());
      row.put("mrr_rs", CrmMoney.paiseToRupees(r.mrrPaise()));
      row.put("churned_at", r.churnedAt());
      row.put("reason", r.reason());
      churnLog.add(row);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("chips", chips);
    data.put("save_play_banner", banner);
    data.put("churn_reasons_chart", reasons);
    data.put("upcoming_renewals", upcoming);
    data.put("churn_log", churnLog);
    return data;
  }

  public PagedResult listUpcoming(
      MedmatePrincipal principal,
      Integer days,
      String riskLevel,
      UUID csmId,
      Integer page,
      Integer limit) {
    requireView(principal);
    int windowDays = days == null ? DEFAULT_RENEWAL_DAYS : days;
    if (windowDays < 1 || windowDays > MAX_RENEWAL_DAYS) {
      throw new AppException("VALIDATION_ERROR", "days must be between 1 and 90", 400);
    }
    String risk = RenewalRiskLevel.requireFilter(riskLevel);
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    Instant windowEnd = now.plus(windowDays, ChronoUnit.DAYS);
    int offset = (p - 1) * lim;
    List<Map<String, Object>> rows = new ArrayList<>();
    for (SaasRenewalChurnStore.UpcomingRow r :
        store.listUpcoming(now, windowEnd, risk, csmId, offset, lim)) {
      rows.add(toUpcomingMap(r, today, true));
    }
    long total = store.countUpcoming(now, windowEnd, risk, csmId);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("upcoming_renewals", rows);
    return new PagedResult(data, PaginationMeta.of(p, lim, total));
  }

  @Transactional
  public Map<String, Object> manualRenew(
      MedmatePrincipal principal,
      UUID accountId,
      Boolean waiveFee,
      String reason,
      String idempotencyKey) {
    requireWrite(principal);
    String key = requireIdempotencyKey(idempotencyKey);
    CrmAccount account = requireAccount(accountId);
    SaasSubscription sub =
        subs.findByAccountId(accountId)
            .orElseThrow(
                () -> new AppException("SUBSCRIPTION_NOT_FOUND", "Subscription not found", 404));
    if (SubscriptionStatus.EXPIRED.equals(sub.status())
        || SubscriptionStatus.CANCELLED.equals(sub.status())) {
      throw new AppException("SUBSCRIPTION_NOT_ACTIVE", "Subscription is not active", 409);
    }
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    LocalDate renewalDay = LocalDate.ofInstant(sub.renewalDate(), ZoneOffset.UTC);
    long absDays = Math.abs(ChronoUnit.DAYS.between(today, renewalDay));
    if (absDays > MANUAL_RENEW_WINDOW_DAYS) {
      throw new AppException("SUBSCRIPTION_NOT_DUE", "Renewal date is not within 7 days", 400);
    }
    SaasPlan plan =
        plans
            .findPlanById(sub.planId())
            .orElseThrow(() -> new AppException("PLAN_NOT_FOUND", "Plan not found", 404));
    if (PlanNames.FREE.equals(plan.name())) {
      throw new AppException("VALIDATION_ERROR", "FREE plan cannot be renewed", 400);
    }
    boolean waive = Boolean.TRUE.equals(waiveFee);
    Instant newRenewal = BillingCycle.advance(sub.renewalDate(), sub.billingCycle());
    LocalDate periodFrom = renewalDay;
    LocalDate periodTo = LocalDate.ofInstant(newRenewal, ZoneOffset.UTC);
    List<InvoiceIssuingPort.LineDraft> lines;
    long amountPaise;
    String chargeKey = "manual-renew:" + sub.id() + ":" + renewalDay;
    if (waive) {
      lines =
          List.of(
              new InvoiceIssuingPort.LineDraft(
                  "Fee waiver - " + plan.name(), 0L, InvoiceLineItemType.CREDIT));
      amountPaise = 0L;
    } else {
      long planAmount = BillingCycle.cyclePricePaise(plan.priceMonthlyPaise(), sub.billingCycle());
      lines = billingLines(accountId, plan, sub.billingCycle(), planAmount);
      amountPaise = sumPaise(lines);
      payments.charge(accountId, amountPaise, chargeKey, key);
    }
    UUID invoiceId =
        invoices.issue(
            accountId,
            sub.id(),
            plan.name(),
            periodFrom,
            periodTo,
            periodFrom,
            lines,
            InvoiceStatus.PAID,
            now,
            waive ? "WAIVER" : "UPI",
            waive ? "manual-renew:" + (reason == null ? plan.name() : reason) : chargeKey);
    SaasSubscription updated =
        new SaasSubscription(
            sub.id(),
            sub.accountId(),
            sub.planId(),
            null,
            SubscriptionStatus.ACTIVE,
            sub.billingCycle(),
            newRenewal,
            null,
            sub.autoRenew(),
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
    subs.updateAccountDenorm(accountId, plan.name(), SubscriptionStatus.ACTIVE, now);

    long charged = waive ? 0L : SaasGst.totalWithGstPaise(amountPaise);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("account_id", account.id());
    data.put("invoice_id", invoiceId);
    data.put("amount_charged_rs", CrmMoney.paiseToRupees(charged));
    data.put("new_renewal_date", periodTo.toString());
    data.put("waive_fee", waive);
    data.put("renewed_by", principal.subject());
    data.put("renewed_at", now);
    return data;
  }

  @Transactional
  public Map<String, Object> logChurnSurvey(
      MedmatePrincipal principal, UUID accountId, String reason, String notes) {
    requireWrite(principal);
    String validReason = ChurnReason.requireValid(reason);
    requireAccount(accountId);
    Instant now = clock.instant();
    ChurnSurvey survey =
        store.insertSurvey(
            new ChurnSurvey(
                Ids.newId(),
                accountId,
                validReason,
                notes == null || notes.isBlank() ? null : notes.trim(),
                principal.subject(),
                now));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("churn_survey_id", survey.id());
    data.put("account_id", survey.accountId());
    data.put("reason", survey.reason());
    data.put("logged_by", survey.loggedBy());
    data.put("logged_at", survey.createdAt());
    return data;
  }

  public Map<String, Object> churnAnalysis(MedmatePrincipal principal, String periodRaw) {
    requireView(principal);
    String period = normalizePeriod(periodRaw);
    Instant now = clock.instant();
    Instant periodStart = periodStart(now, period);
    LocalDate asOf = LocalDate.ofInstant(now, ZoneOffset.UTC);

    List<SaasRenewalChurnStore.ReasonCount> counts = store.churnReasons(periodStart, now);
    long totalReasons = 0L;
    for (SaasRenewalChurnStore.ReasonCount c : counts) {
      totalReasons += c.count();
    }
    List<Map<String, Object>> chart = new ArrayList<>();
    for (SaasRenewalChurnStore.ReasonCount c : counts) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("reason", c.reason());
      row.put("count", c.count());
      row.put("pct", ChurnMath.pctOf(c.count(), totalReasons));
      chart.add(row);
    }

    List<Map<String, Object>> cohorts = new ArrayList<>();
    for (SaasRenewalChurnStore.CohortRate c : store.cohortChurnRates(asOf)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("cohort_month", c.cohortMonth());
      row.put("month_1_churn_pct", c.month1ChurnPct());
      row.put("month_3_churn_pct", c.month3ChurnPct());
      row.put("month_6_churn_pct", c.month6ChurnPct());
      cohorts.add(row);
    }

    long churned = store.countChurnedLogos(periodStart, now);
    long lowAdoption = store.countChurnedWithLowAdoption(periodStart, now);
    long missedPay = store.countChurnedWithMissedPayments(periodStart, now);
    List<Map<String, Object>> indicators = new ArrayList<>();
    indicators.add(indicator("Low module adoption (<20%)", lowAdoption, churned));
    indicators.add(indicator("Missed 2+ payment cycles", missedPay, churned));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("period", period);
    data.put("churn_reasons_chart", chart);
    data.put("cohort_churn_rates", cohorts);
    data.put("at_risk_indicators", indicators);
    return data;
  }

  @Transactional
  public void processWinbacks() {
    Instant now = clock.instant();
    LocalDate targetDay = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(7);
    Instant dayStart = targetDay.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant dayEnd = targetDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    for (UUID accountId : store.findWinbackDue(dayStart, dayEnd)) {
      outbox.publish(WINBACK_EVENT, accountId, Map.of("account_id", accountId.toString()));
    }
  }

  @Transactional
  public void processAtRiskCsmAlerts() {
    Instant now = clock.instant();
    Instant windowEnd = now.plus(DEFAULT_RENEWAL_DAYS, ChronoUnit.DAYS);
    for (SaasRenewalChurnStore.AtRiskAlertRow row : store.findAtRiskRenewals(now, windowEnd)) {
      outbox.publish(
          AT_RISK_CSM_EVENT,
          row.subscriptionId(),
          Map.of(
              "account_id", row.accountId().toString(),
              "subscription_id", row.subscriptionId().toString(),
              "health_score", row.healthScore()));
    }
  }

  @Transactional
  public void processMonthlyChurnReport() {
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    if (today.getDayOfMonth() != 1) {
      return;
    }
    LocalDate priorMonth = today.minusMonths(1).withDayOfMonth(1);
    Instant periodStart = priorMonth.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant periodEnd = today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    long churned = store.countChurnedLogos(periodStart, periodEnd);
    long start = store.countStartOfPeriodLogos(periodStart, periodEnd);
    long mrr = store.sumMrrChurnedPaise(periodStart, periodEnd);
    BigDecimal logoPct = ChurnMath.logoChurnPct(churned, start);
    Map<String, Object> payload =
        Map.of(
            "period_start", priorMonth.toString(),
            "period_end", today.minusDays(1).toString(),
            "churned_logos", churned,
            "logo_churn_pct", logoPct,
            "mrr_churned_paise", mrr);
    outbox.publish(MONTHLY_REPORT_EVENT, Ids.newId(), payload);
    log.info("crm.churn.monthly_report {}", payload);
  }

  private Map<String, Object> toUpcomingMap(
      SaasRenewalChurnStore.UpcomingRow r, LocalDate today, boolean includeDays) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("account_id", r.accountId());
    row.put("pharmacy_name", r.pharmacyName());
    row.put("plan", r.plan());
    row.put("mrr_rs", CrmMoney.paiseToRupees(r.mrrPaise()));
    row.put("renewal_date", r.renewalDate() == null ? null : r.renewalDate().toString());
    if (includeDays) {
      long days = r.renewalDate() == null ? 0L : ChronoUnit.DAYS.between(today, r.renewalDate());
      row.put("days_until_renewal", days);
    }
    row.put("auto_renew", r.autoRenew());
    row.put("risk_level", RenewalRiskLevel.fromHealthScore(r.healthScore()));
    row.put("health_score", r.healthScore());
    if (includeDays) {
      row.put("last_save_play_at", r.lastSavePlayAt());
    }
    row.put("assigned_csm", r.assignedCsm());
    return row;
  }

  private static List<Map<String, Object>> reasonChart(
      List<SaasRenewalChurnStore.ReasonCount> counts) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (SaasRenewalChurnStore.ReasonCount c : counts) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("reason", c.reason());
      row.put("count", c.count());
      out.add(row);
    }
    return out;
  }

  private static Map<String, Object> indicator(String name, long withThis, long churned) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("indicator", name);
    row.put("churned_with_this", withThis);
    row.put("pct_of_churned", ChurnMath.pctOf(withThis, churned));
    return row;
  }

  private static String normalizePeriod(String raw) {
    if (raw == null || raw.isBlank()) {
      return "last_90d";
    }
    String v = raw.trim().toLowerCase(Locale.ROOT);
    if (!"last_30d".equals(v) && !"last_90d".equals(v) && !"last_6m".equals(v)) {
      throw new AppException(
          "VALIDATION_ERROR", "period must be last_30d, last_90d, or last_6m", 400);
    }
    return v;
  }

  private static Instant periodStart(Instant now, String period) {
    return switch (period) {
      case "last_30d" -> now.minus(30, ChronoUnit.DAYS);
      case "last_6m" -> now.minus(183, ChronoUnit.DAYS);
      default -> now.minus(90, ChronoUnit.DAYS);
    };
  }

  private List<InvoiceIssuingPort.LineDraft> billingLines(
      UUID accountId, SaasPlan plan, String cycle, long planAmountPaise) {
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
    return lines;
  }

  private static long sumPaise(List<InvoiceIssuingPort.LineDraft> lines) {
    long sum = 0L;
    for (InvoiceIssuingPort.LineDraft line : lines) {
      sum = Math.addExact(sum, line.amountPaise());
    }
    return Math.max(0L, sum);
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

  private static String requireIdempotencyKey(String key) {
    if (key == null || key.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "Idempotency-Key is required", 400);
    }
    if (key.length() > 128) {
      throw new AppException("VALIDATION_ERROR", "Idempotency-Key max 128 characters", 400);
    }
    return key.trim();
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
}

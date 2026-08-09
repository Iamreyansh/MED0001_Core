package com.nammamedmate.crm.application;

import com.nammamedmate.crm.adapter.out.export.SimplePdfExporter;
import com.nammamedmate.crm.application.port.out.SaasAnalyticsStore;
import com.nammamedmate.crm.application.port.out.SaasAnalyticsStore.CohortRetentionRow;
import com.nammamedmate.crm.application.port.out.SaasAnalyticsStore.PlanMrrRow;
import com.nammamedmate.crm.application.port.out.SaasInvoicePdfPort;
import com.nammamedmate.crm.domain.AnalyticsMath;
import com.nammamedmate.crm.domain.ChurnMath;
import com.nammamedmate.crm.domain.CrmMoney;
import com.nammamedmate.crm.domain.SaasMetricsSnapshot;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SaasAnalyticsService {

  static final int RATE_LIMIT = 10;
  static final int RATE_WINDOW_SECONDS = 60;
  static final Duration REPORT_TTL = Duration.ofHours(1);
  private static final Set<String> PERIODS = Set.of("MONTH", "QUARTER", "YEAR", "CUSTOM");
  private static final Set<String> REPORT_PERIODS = Set.of("MONTH", "QUARTER", "YEAR");
  private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

  private final SaasAnalyticsStore store;
  private final SaasInvoicePdfPort reports;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public SaasAnalyticsService(
      SaasAnalyticsStore store, SaasInvoicePdfPort reports, RateLimiter rateLimiter, Clock clock) {
    this.store = store;
    this.reports = reports;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public Map<String, Object> revenue(
      MedmatePrincipal principal, String period, String from, String to, String plan) {
    requireAnalytics(principal);
    rateLimit(principal);
    LocalDate ref = resolveReferenceMonth(period, from, to);
    SaasMetricsSnapshot snap = ensureMetrics(ref);
    String planFilter = blankToNull(plan);

    long liveMrr = store.sumActiveMrrPaise(planFilter);
    long mrr = planFilter == null ? snap.mrrPaise() : liveMrr;
    long arr = AnalyticsMath.arrPaise(mrr);
    LocalDate priorMonth = ref.minusMonths(1);
    long priorMrr =
        store
            .findMetrics(priorMonth)
            .map(SaasMetricsSnapshot::mrrPaise)
            .orElse(Math.max(0L, snap.startMrrPaise()));

    Map<String, Object> kpi = new LinkedHashMap<>();
    kpi.put("mrr_rs", CrmMoney.paiseToRupees(mrr));
    kpi.put("arr_rs", CrmMoney.paiseToRupees(arr));
    kpi.put("mrr_growth_pct", AnalyticsMath.mrrGrowthPct(mrr, priorMrr));
    kpi.put("nrr_pct", snap.nrrPct());
    kpi.put("grr_pct", snap.grrPct());
    kpi.put("quick_ratio", snap.quickRatio());
    kpi.put("magic_number", snap.magicNumber());
    kpi.put("ltv_cac_ratio", AnalyticsMath.ltvCacRatio(snap.ltvPaise(), snap.cacPaise()));
    kpi.put("arpa_rs", CrmMoney.paiseToRupees(snap.arpaPaise()));

    LocalDate trendFrom = ref.minusMonths(11);
    List<Map<String, Object>> trend = new ArrayList<>();
    for (SaasMetricsSnapshot t : store.listMetrics(trendFrom, ref)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("month", YEAR_MONTH.format(t.metricMonth()));
      row.put("mrr_rs", CrmMoney.paiseToRupees(t.mrrPaise()));
      trend.add(row);
    }
    if (trend.isEmpty()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("month", YEAR_MONTH.format(ref));
      row.put("mrr_rs", CrmMoney.paiseToRupees(mrr));
      trend.add(row);
    }

    List<Map<String, Object>> byPlan = new ArrayList<>();
    for (PlanMrrRow p : store.mrrByPlan(planFilter)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("plan", p.plan());
      row.put("mrr_rs", CrmMoney.paiseToRupees(p.mrrPaise()));
      row.put("account_count", p.accountCount());
      byPlan.add(row);
    }

    Map<String, Object> movement = movementMap(snap);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("period", normalizePeriod(period));
    data.put("reference_month", YEAR_MONTH.format(ref));
    data.put("kpi_grid", kpi);
    data.put("mrr_trend", trend);
    data.put("mrr_by_plan", byPlan);
    data.put("mrr_movement", movement);
    return data;
  }

  public Map<String, Object> mrrBridge(MedmatePrincipal principal, String month) {
    requireAnalytics(principal);
    rateLimit(principal);
    LocalDate ref = parseYearMonth(month, currentMonth());
    SaasMetricsSnapshot snap = ensureMetrics(ref);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("month", YEAR_MONTH.format(ref));
    data.put("start_mrr_rs", CrmMoney.paiseToRupees(snap.startMrrPaise()));
    data.put("new_mrr_rs", CrmMoney.paiseToRupees(snap.newMrrPaise()));
    data.put("expansion_mrr_rs", CrmMoney.paiseToRupees(snap.expansionMrrPaise()));
    data.put("contraction_mrr_rs", CrmMoney.paiseToRupees(snap.contractionMrrPaise()));
    data.put("churn_mrr_rs", CrmMoney.paiseToRupees(snap.churnMrrPaise()));
    data.put("net_new_mrr_rs", CrmMoney.paiseToRupees(snap.netNewMrrPaise()));
    data.put("end_mrr_rs", CrmMoney.paiseToRupees(snap.mrrPaise()));
    data.put("new_logos", snap.newLogos());
    data.put("churned_logos", snap.churnedLogos());
    data.put("expansion_accounts", snap.expansionAccounts());
    data.put("contraction_accounts", snap.contractionAccounts());
    return data;
  }

  public Map<String, Object> cohort(
      MedmatePrincipal principal, String cohortFrom, String cohortTo) {
    requireAnalytics(principal);
    rateLimit(principal);
    LocalDate asOf = currentMonth();
    LocalDate from = parseYearMonth(cohortFrom, asOf.minusMonths(5));
    LocalDate to = parseYearMonth(cohortTo, asOf);
    if (from.isAfter(to)) {
      throw new AppException("VALIDATION_ERROR", "cohort_from must be <= cohort_to", 400);
    }
    List<CohortRetentionRow> rows = store.listCohortRetention(from, to);
    if (rows.isEmpty()) {
      rows = store.computeLiveCohortRetention(from, to, asOf);
    }
    Map<LocalDate, List<CohortRetentionRow>> byCohort = new LinkedHashMap<>();
    int maxMonths = 0;
    for (CohortRetentionRow r : rows) {
      byCohort.computeIfAbsent(r.cohortMonth(), k -> new ArrayList<>()).add(r);
      maxMonths = Math.max(maxMonths, r.monthsSince());
    }
    List<Integer> labels = new ArrayList<>();
    for (int i = 0; i <= maxMonths; i++) {
      labels.add(i);
    }
    List<Map<String, Object>> grid = new ArrayList<>();
    for (Map.Entry<LocalDate, List<CohortRetentionRow>> e : byCohort.entrySet()) {
      List<CohortRetentionRow> series = e.getValue();
      series.sort((a, b) -> Integer.compare(a.monthsSince(), b.monthsSince()));
      int starting = series.getFirst().startingAccounts();
      List<BigDecimal> pcts = new ArrayList<>();
      for (int i = 0; i <= maxMonths; i++) {
        final int month = i;
        BigDecimal pct =
            series.stream()
                .filter(r -> r.monthsSince() == month)
                .map(CohortRetentionRow::retentionPct)
                .findFirst()
                .orElse(null);
        pcts.add(pct);
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("cohort_month", YEAR_MONTH.format(e.getKey()));
      row.put("starting_accounts", starting);
      row.put("retention_pcts", pcts);
      grid.add(row);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("cohort_retention", grid);
    data.put("months_since_labels", labels);
    return data;
  }

  public Map<String, Object> unitEconomics(MedmatePrincipal principal) {
    requireAnalytics(principal);
    rateLimit(principal);
    SaasMetricsSnapshot snap = ensureMetrics(currentMonth());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("arpa_rs", CrmMoney.paiseToRupees(snap.arpaPaise()));
    data.put("avg_ltv_rs", CrmMoney.paiseToRupees(snap.ltvPaise()));
    data.put("avg_cac_rs", CrmMoney.paiseToRupees(snap.cacPaise()));
    data.put("ltv_cac_ratio", AnalyticsMath.ltvCacRatio(snap.ltvPaise(), snap.cacPaise()));
    data.put(
        "payback_months",
        AnalyticsMath.paybackMonths(
            snap.cacPaise(), snap.arpaPaise(), AnalyticsMath.DEFAULT_GROSS_MARGIN_PCT));
    data.put("gross_margin_pct", AnalyticsMath.DEFAULT_GROSS_MARGIN_PCT);
    data.put("monthly_revenue_churn_pct", snap.logoChurnPct());
    data.put("computed_at", snap.computedAt().toString());
    return data;
  }

  public Map<String, Object> report(
      MedmatePrincipal principal, String period, String month, String format) {
    requireAnalytics(principal);
    rateLimit(principal);
    String p = normalizeReportPeriod(period);
    String fmt = format == null ? "PDF" : format.trim().toUpperCase(Locale.ROOT);
    if (!"PDF".equals(fmt) && !"CSV".equals(fmt)) {
      throw new AppException("VALIDATION_ERROR", "format must be PDF or CSV", 400);
    }
    LocalDate ref = parseYearMonth(month, currentMonth());
    SaasMetricsSnapshot snap = ensureMetrics(ref);
    String periodLabel = YEAR_MONTH.format(ref);
    String objectKey = "saas-analytics-" + periodLabel + "." + fmt.toLowerCase(Locale.ROOT);
    byte[] bytes =
        "CSV".equals(fmt) ? buildCsv(snap, p, periodLabel) : buildPdf(snap, p, periodLabel);
    reports.put(objectKey, bytes);
    SaasInvoicePdfPort.SignedUrl signed = reports.signedGet(objectKey, REPORT_TTL);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("report_url", signed.url());
    data.put("expires_at", signed.expiresAt().toString());
    data.put("format", fmt);
    data.put("period", periodLabel);
    return data;
  }

  @Transactional
  public void computeMonthlyBatch() {
    LocalDate current = currentMonth();
    ensureMetrics(current.minusMonths(1));
    ensureMetrics(current);
    refreshCohortCache(current);
  }

  SaasMetricsSnapshot ensureMetrics(LocalDate monthStart) {
    return store.findMetrics(monthStart).orElseGet(() -> computeAndCache(monthStart));
  }

  @Transactional
  public SaasMetricsSnapshot computeAndCache(LocalDate monthStart) {
    LocalDate monthEnd = monthStart.plusMonths(1);
    Instant periodStart = monthStart.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant periodEnd = monthEnd.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant now = clock.instant();

    long endMrr = store.sumActiveMrrPaise(null);
    long paying = store.countPayingAccounts(null);
    long newMrr = store.sumNewLogoMrrPaise(monthStart, monthEnd);
    int newLogos = store.countNewLogos(monthStart, monthEnd);
    long churnMrr = store.sumChurnMrrPaise(periodStart, periodEnd);
    int churnedLogos = store.countChurnedLogos(periodStart, periodEnd);
    long expansionMrr = store.sumExpansionMrrPaise(periodStart, periodEnd);
    int expansionAccounts = store.countExpansionAccounts(periodStart, periodEnd);
    long contractionMrr = store.sumContractionMrrPaise(periodStart, periodEnd);
    int contractionAccounts = store.countContractionAccounts(periodStart, periodEnd);

    long inferredStart =
        Math.max(
            0L,
            endMrr - AnalyticsMath.netNewMrrPaise(newMrr, expansionMrr, contractionMrr, churnMrr));
    long startMrr =
        store
            .findMetrics(monthStart.minusMonths(1))
            .map(SaasMetricsSnapshot::mrrPaise)
            .orElse(inferredStart);

    // Keep bridge identity: start + net_new = end (adjust residual into expansion/contraction).
    long netImplied = endMrr - startMrr;
    long netFromParts =
        AnalyticsMath.netNewMrrPaise(newMrr, expansionMrr, contractionMrr, churnMrr);
    long residual = netImplied - netFromParts;
    if (residual > 0L) {
      expansionMrr += residual;
    } else if (residual < 0L) {
      contractionMrr += -residual;
    }
    long netNew = AnalyticsMath.netNewMrrPaise(newMrr, expansionMrr, contractionMrr, churnMrr);

    BigDecimal nrr = AnalyticsMath.nrrPct(startMrr, expansionMrr, churnMrr);
    BigDecimal grr = AnalyticsMath.grrPct(startMrr, churnMrr);
    BigDecimal quick = AnalyticsMath.quickRatio(newMrr, expansionMrr, contractionMrr, churnMrr);
    long startLogos = paying + churnedLogos;
    BigDecimal logoChurn = ChurnMath.logoChurnPct(churnedLogos, startLogos);
    long arpa = AnalyticsMath.arpaPaise(endMrr, paying);
    long smMonth = store.smSpendPaise(monthStart);
    long cac = AnalyticsMath.cacPaise(smMonth, newLogos);
    long ltv = AnalyticsMath.ltvPaise(arpa, AnalyticsMath.DEFAULT_GROSS_MARGIN_PCT, logoChurn);

    LocalDate qStart = quarterStart(monthStart);
    LocalDate priorQStart = qStart.minusMonths(3);
    LocalDate priorQEnd = qStart.minusMonths(1);
    long priorQSpend = store.sumSmSpendPaise(priorQStart, priorQEnd);
    long mrrThreeAgo =
        store
            .findMetrics(monthStart.minusMonths(3))
            .map(SaasMetricsSnapshot::mrrPaise)
            .orElse(startMrr);
    BigDecimal magic = AnalyticsMath.magicNumber(endMrr - mrrThreeAgo, priorQSpend);

    SaasMetricsSnapshot snap =
        new SaasMetricsSnapshot(
            monthStart,
            endMrr,
            AnalyticsMath.arrPaise(endMrr),
            arpa,
            nrr,
            grr,
            quick,
            magic,
            ltv,
            cac,
            logoChurn,
            startMrr,
            newMrr,
            expansionMrr,
            contractionMrr,
            churnMrr,
            netNew,
            newLogos,
            churnedLogos,
            expansionAccounts,
            contractionAccounts,
            now);
    store.upsertMetrics(snap);
    return snap;
  }

  void refreshCohortCache(LocalDate asOf) {
    LocalDate from = asOf.minusMonths(11);
    List<CohortRetentionRow> rows = store.computeLiveCohortRetention(from, asOf, asOf);
    store.replaceCohortRetention(rows);
  }

  private Map<String, Object> movementMap(SaasMetricsSnapshot snap) {
    Map<String, Object> movement = new LinkedHashMap<>();
    movement.put("new_mrr_rs", CrmMoney.paiseToRupees(snap.newMrrPaise()));
    movement.put("expansion_mrr_rs", CrmMoney.paiseToRupees(snap.expansionMrrPaise()));
    movement.put("contraction_mrr_rs", CrmMoney.paiseToRupees(snap.contractionMrrPaise()));
    movement.put("churn_mrr_rs", CrmMoney.paiseToRupees(snap.churnMrrPaise()));
    movement.put("net_new_mrr_rs", CrmMoney.paiseToRupees(snap.netNewMrrPaise()));
    movement.put("end_mrr_rs", CrmMoney.paiseToRupees(snap.mrrPaise()));
    return movement;
  }

  private byte[] buildCsv(SaasMetricsSnapshot snap, String period, String label) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        "period,reference_month,mrr_rs,arr_rs,nrr_pct,grr_pct,quick_ratio,arpa_rs,ltv_rs,cac_rs\n");
    sb.append(period)
        .append(',')
        .append(label)
        .append(',')
        .append(CrmMoney.paiseToRupees(snap.mrrPaise()))
        .append(',')
        .append(CrmMoney.paiseToRupees(snap.arrPaise()))
        .append(',')
        .append(snap.nrrPct())
        .append(',')
        .append(snap.grrPct())
        .append(',')
        .append(snap.quickRatio())
        .append(',')
        .append(CrmMoney.paiseToRupees(snap.arpaPaise()))
        .append(',')
        .append(CrmMoney.paiseToRupees(snap.ltvPaise()))
        .append(',')
        .append(CrmMoney.paiseToRupees(snap.cacPaise()))
        .append('\n');
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  private byte[] buildPdf(SaasMetricsSnapshot snap, String period, String label) {
    List<String> lines =
        List.of(
            "Period: " + period,
            "Month: " + label,
            "MRR: " + CrmMoney.paiseToRupees(snap.mrrPaise()),
            "ARR: " + CrmMoney.paiseToRupees(snap.arrPaise()),
            "NRR%: " + snap.nrrPct(),
            "GRR%: " + snap.grrPct(),
            "Quick ratio: " + snap.quickRatio(),
            "ARPA: " + CrmMoney.paiseToRupees(snap.arpaPaise()),
            "LTV: " + CrmMoney.paiseToRupees(snap.ltvPaise()),
            "CAC: " + CrmMoney.paiseToRupees(snap.cacPaise()));
    return SimplePdfExporter.export("SaaS Revenue Analytics", lines);
  }

  private void rateLimit(MedmatePrincipal principal) {
    String key = "crm-analytics:" + principal.subject();
    if (!rateLimiter.tryAcquire(key, RATE_LIMIT, RATE_WINDOW_SECONDS)) {
      int retry = rateLimiter.secondsUntilAvailable(key, RATE_LIMIT, RATE_WINDOW_SECONDS);
      throw new AppException(
          "RATE_LIMITED", "Analytics rate limit exceeded", 429, Math.max(1, retry));
    }
  }

  private LocalDate currentMonth() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).withDayOfMonth(1);
  }

  private LocalDate resolveReferenceMonth(String period, String from, String to) {
    String p = normalizePeriod(period);
    LocalDate todayMonth = currentMonth();
    if ("CUSTOM".equals(p)) {
      if (isBlank(from) || isBlank(to)) {
        throw new AppException("VALIDATION_ERROR", "from and to required for CUSTOM", 400);
      }
      LocalDate toMonth = parseYearMonth(to, todayMonth);
      LocalDate fromMonth = parseYearMonth(from, todayMonth);
      if (fromMonth.isAfter(toMonth)) {
        throw new AppException("VALIDATION_ERROR", "from must be <= to", 400);
      }
      return toMonth;
    }
    if ("QUARTER".equals(p)) {
      return quarterStart(todayMonth).plusMonths(2);
    }
    if ("YEAR".equals(p)) {
      return LocalDate.of(todayMonth.getYear(), 12, 1);
    }
    return todayMonth;
  }

  private static LocalDate quarterStart(LocalDate month) {
    int q = ((month.getMonthValue() - 1) / 3) * 3 + 1;
    return LocalDate.of(month.getYear(), q, 1);
  }

  private static String normalizePeriod(String period) {
    if (isBlank(period)) {
      return "MONTH";
    }
    String p = period.trim().toUpperCase(Locale.ROOT);
    if (!PERIODS.contains(p)) {
      throw new AppException("VALIDATION_ERROR", "period must be MONTH|QUARTER|YEAR|CUSTOM", 400);
    }
    return p;
  }

  private static String normalizeReportPeriod(String period) {
    if (isBlank(period)) {
      return "MONTH";
    }
    String p = period.trim().toUpperCase(Locale.ROOT);
    if (!REPORT_PERIODS.contains(p)) {
      throw new AppException("VALIDATION_ERROR", "period must be MONTH|QUARTER|YEAR", 400);
    }
    return p;
  }

  private static LocalDate parseYearMonth(String raw, LocalDate defaultMonth) {
    if (isBlank(raw)) {
      return defaultMonth;
    }
    try {
      return YearMonth.parse(raw.trim(), YEAR_MONTH).atDay(1);
    } catch (DateTimeParseException e) {
      throw new AppException("VALIDATION_ERROR", "month must be YYYY-MM", 400);
    }
  }

  private static String blankToNull(String value) {
    return isBlank(value) ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static void requireAnalytics(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Finance analytics access required", 403);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_FINANCE) {
      throw new AppException("FORBIDDEN", "Finance analytics access required", 403);
    }
  }
}

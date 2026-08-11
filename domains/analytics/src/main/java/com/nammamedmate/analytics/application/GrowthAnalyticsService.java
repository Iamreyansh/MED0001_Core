package com.nammamedmate.analytics.application;

import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.AcquisitionRow;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.CohortCell;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.GrowthTotals;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.Month1Retention;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.OrderTrendPoint;
import com.nammamedmate.analytics.application.port.out.PlatformGrowthStore.SpendRow;
import com.nammamedmate.analytics.domain.AnalyticsMath;
import com.nammamedmate.analytics.domain.PeriodResolver;
import com.nammamedmate.analytics.domain.PeriodResolver.DateWindow;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** EPIC-016 STORY-003 growth & cohort analytics. */
@Service
public class GrowthAnalyticsService {

  static final int DEFAULT_COHORT_COUNT = 12;
  static final int MAX_COHORT_COUNT = 26;
  static final int HEATMAP_WEEKS = 13; // elapsed 0..12

  private final PlatformGrowthStore store;
  private final Clock clock;

  public GrowthAnalyticsService(PlatformGrowthStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public Map<String, Object> growth(
      MedmatePrincipal principal, String period, String dateFrom, String dateTo) {
    requireGrowth(principal);
    DateWindow window = PeriodResolver.resolveGrowth(period, dateFrom, dateTo, clock);
    boolean aggregated = PeriodResolver.useAggregated(window);
    GrowthTotals current = loadGrowth(window, aggregated);
    GrowthTotals prior = loadGrowth(window.priorWindow(clock), aggregated);

    BigDecimal repeat =
        AnalyticsMath.ratioPct(current.repeatCustomers(), current.activeCustomers());
    BigDecimal priorRepeat =
        AnalyticsMath.ratioPct(prior.repeatCustomers(), prior.activeCustomers());

    LocalDate todayIst = LocalDate.now(clock.withZone(PeriodResolver.IST));
    Optional<Month1Retention> month1 = store.month1Retention(todayIst);
    BigDecimal month1Pct =
        month1
            .map(Month1Retention::retentionPct)
            .orElse(BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP));
    // Prior month1 for wow: look one month earlier window via same helper on prior date
    Optional<Month1Retention> priorMonth1 = store.month1Retention(todayIst.minusWeeks(1));
    BigDecimal priorMonth1Pct =
        priorMonth1
            .map(Month1Retention::retentionPct)
            .orElse(BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP));

    Map<String, Object> kpis = new LinkedHashMap<>();
    kpis.put(
        "active_customers",
        countKpi(
            current.activeCustomers(),
            AnalyticsMath.wowDeltaPct(current.activeCustomers(), prior.activeCustomers())));
    kpis.put(
        "new_customers",
        countKpi(
            current.newCustomers(),
            AnalyticsMath.wowDeltaPct(current.newCustomers(), prior.newCustomers())));
    kpis.put(
        "repeat_rate_pct",
        pctKpi(repeat, repeat.subtract(priorRepeat).setScale(1, RoundingMode.HALF_UP)));

    Map<String, Object> month1Kpi = new LinkedHashMap<>();
    month1Kpi.put(
        "value", month1Pct.scale() > 1 ? month1Pct.setScale(1, RoundingMode.HALF_UP) : month1Pct);
    month1Kpi.put("cohort_week", month1.map(Month1Retention::cohortWeek).orElse(null));
    month1Kpi.put(
        "wow_delta_pct", month1Pct.subtract(priorMonth1Pct).setScale(1, RoundingMode.HALF_UP));
    kpis.put("month1_retention_pct", month1Kpi);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("period", window.period());
    data.put("kpis", kpis);
    data.put("generated_at", clock.instant().toString());
    return data;
  }

  public Map<String, Object> cohort(MedmatePrincipal principal, Integer cohortCountRaw) {
    requireGrowth(principal);
    int cohortCount = cohortCountRaw == null ? DEFAULT_COHORT_COUNT : cohortCountRaw;
    if (cohortCount > MAX_COHORT_COUNT) {
      throw new AppException("COHORT_COUNT_TOO_LARGE", "cohort_count max is 26", 422);
    }
    if (cohortCount < 1) {
      throw new AppException("INVALID_PERIOD", "cohort_count must be >= 1", 400);
    }

    // AC-008: read precomputed only — never trigger refresh from this endpoint.
    List<CohortCell> cells = store.cohortMatrix(cohortCount);
    Instant lastComputed = store.cohortLastComputedAt().orElse(null);

    LocalDate todayIst = LocalDate.now(clock.withZone(PeriodResolver.IST));
    LocalDate currentWeekStart = AnalyticsMath.isoWeekStart(todayIst);

    Map<String, List<CohortCell>> byWeek = new LinkedHashMap<>();
    Map<String, Integer> sizes = new LinkedHashMap<>();
    for (CohortCell cell : cells) {
      byWeek.computeIfAbsent(cell.cohortWeek(), k -> new ArrayList<>()).add(cell);
      sizes.putIfAbsent(cell.cohortWeek(), cell.cohortSize());
    }

    List<Integer> weeksHeader = new ArrayList<>();
    for (int w = 0; w < HEATMAP_WEEKS; w++) {
      weeksHeader.add(w);
    }

    List<Map<String, Object>> cohorts = new ArrayList<>();
    for (Map.Entry<String, List<CohortCell>> e : byWeek.entrySet()) {
      String week = e.getKey();
      LocalDate cohortStart = parseCohortWeekStart(week);
      Map<Integer, BigDecimal> pctByElapsed = new LinkedHashMap<>();
      for (CohortCell c : e.getValue()) {
        pctByElapsed.put(c.elapsedWeek(), c.retentionPct());
      }
      List<BigDecimal> retentionPcts = new ArrayList<>(HEATMAP_WEEKS);
      for (int elapsed = 0; elapsed < HEATMAP_WEEKS; elapsed++) {
        LocalDate targetWeek = cohortStart.plusWeeks(elapsed);
        if (targetWeek.isAfter(currentWeekStart)) {
          retentionPcts.add(null);
        } else if (elapsed == 0) {
          retentionPcts.add(BigDecimal.valueOf(100).setScale(1, RoundingMode.HALF_UP));
        } else {
          BigDecimal raw = pctByElapsed.get(elapsed);
          retentionPcts.add(raw == null ? null : raw.setScale(1, RoundingMode.HALF_UP));
        }
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("cohort_week", week);
      row.put("cohort_size", sizes.getOrDefault(week, 0));
      row.put("retention_pcts", retentionPcts);
      cohorts.add(row);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("weeks_header", weeksHeader);
    data.put("cohorts", cohorts);
    data.put("last_computed_at", lastComputed == null ? null : lastComputed.toString());
    return data;
  }

  public Map<String, Object> acquisition(
      MedmatePrincipal principal, String period, String dateFrom, String dateTo) {
    requireGrowth(principal);
    DateWindow window = PeriodResolver.resolveGrowth(period, dateFrom, dateTo, clock);
    boolean aggregated = PeriodResolver.useAggregated(window);
    List<AcquisitionRow> rows =
        aggregated
            ? store.aggregatedAcquisition(window.fromDate(), window.toDate())
            : store.liveAcquisition(window.fromInclusive(), window.toExclusive());

    Map<String, BigDecimal> spendBySource = new LinkedHashMap<>();
    for (SpendRow s : store.campaignSpend(window.fromDate(), window.toDate())) {
      spendBySource.put(s.source(), s.spendRs());
    }

    long totalNew = rows.stream().mapToLong(AcquisitionRow::newUsers).sum();
    List<Map<String, Object>> sources = sourceRowsWithPctSum100(rows, totalNew, spendBySource);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("period", window.period());
    data.put("total_new_users", totalNew);
    data.put("sources", sources);
    return data;
  }

  public Map<String, Object> orderTrend(
      MedmatePrincipal principal, String period, String granularityRaw) {
    requireGrowth(principal);
    DateWindow window = PeriodResolver.resolveGrowthTrend(period, clock);
    String granularity = normalizeGranularity(granularityRaw);
    List<OrderTrendPoint> points =
        "WEEKLY".equals(granularity)
            ? store.orderTrendWeekly(window.fromInclusive(), window.toExclusive())
            : store.orderTrendDaily(window.fromInclusive(), window.toExclusive());

    List<Map<String, Object>> trend = new ArrayList<>();
    for (OrderTrendPoint p : points) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("date", p.date().toString());
      row.put("total_orders", p.totalOrders());
      row.put("new_customer_orders", p.newCustomerOrders());
      row.put("returning_customer_orders", p.returningCustomerOrders());
      trend.add(row);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("period", window.period());
    data.put("granularity", granularity);
    data.put("trend", trend);
    return data;
  }

  private GrowthTotals loadGrowth(DateWindow window, boolean aggregated) {
    if (aggregated) {
      return store.aggregatedGrowth(window.fromDate(), window.toDate());
    }
    return store.liveGrowth(window.fromInclusive(), window.toExclusive());
  }

  private static String normalizeGranularity(String raw) {
    if (raw == null || raw.isBlank()) {
      return "DAILY";
    }
    String g = raw.trim().toUpperCase(Locale.ROOT);
    if (!"DAILY".equals(g) && !"WEEKLY".equals(g)) {
      throw new AppException("INVALID_GRANULARITY", "granularity must be DAILY or WEEKLY", 400);
    }
    return g;
  }

  private static LocalDate parseCohortWeekStart(String cohortWeek) {
    // 2026-W17
    String[] parts = cohortWeek.split("-W");
    int year = Integer.parseInt(parts[0]);
    int week = Integer.parseInt(parts[1]);
    return LocalDate.of(year, 1, 4)
        .with(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear(), week)
        .with(java.time.DayOfWeek.MONDAY);
  }

  private static List<Map<String, Object>> sourceRowsWithPctSum100(
      List<AcquisitionRow> rows, long totalNew, Map<String, BigDecimal> spendBySource) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (rows.isEmpty()) {
      return out;
    }
    BigDecimal assigned = BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    for (int i = 0; i < rows.size(); i++) {
      AcquisitionRow r = rows.get(i);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("source", r.source());
      m.put("new_users", r.newUsers());
      BigDecimal pct;
      if (i == rows.size() - 1) {
        pct =
            totalNew == 0L
                ? BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(100)
                    .setScale(1, RoundingMode.HALF_UP)
                    .subtract(assigned)
                    .setScale(1, RoundingMode.HALF_UP);
      } else {
        pct = AnalyticsMath.ratioPct(r.newUsers(), totalNew);
        assigned = assigned.add(pct);
      }
      m.put("pct", pct);
      m.put("orders", r.orders());
      m.put("gmv_paise", r.gmvPaise());
      if ("ORGANIC".equals(r.source())) {
        m.put("cac_rs", 0L);
      } else {
        m.put(
            "cac_rs",
            AnalyticsMath.cacRs(
                spendBySource.getOrDefault(r.source(), BigDecimal.ZERO), r.newUsers()));
      }
      out.add(m);
    }
    return out;
  }

  private static Map<String, Object> countKpi(long value, BigDecimal wow) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("value", value);
    m.put("wow_delta_pct", wow);
    return m;
  }

  private static Map<String, Object> pctKpi(BigDecimal value, BigDecimal wow) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("value", value);
    m.put("wow_delta_pct", wow);
    return m;
  }

  static void requireGrowth(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Growth analytics access required", 403);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Growth analytics access required", 403);
    }
  }
}

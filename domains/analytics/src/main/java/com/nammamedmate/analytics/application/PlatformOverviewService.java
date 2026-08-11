package com.nammamedmate.analytics.application;

import com.nammamedmate.analytics.application.port.out.AnalyticsExportPort;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.CategoryMixRow;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.GmvTrendPoint;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.KpiTotals;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.PaymentMixRow;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.PharmacyLeader;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.RiderLeader;
import com.nammamedmate.analytics.application.port.out.PlatformOverviewStore.ZoneSalesRow;
import com.nammamedmate.analytics.domain.AnalyticsMath;
import com.nammamedmate.analytics.domain.PeriodResolver;
import com.nammamedmate.analytics.domain.PeriodResolver.DateWindow;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** EPIC-016 STORY-001 platform overview analytics (KPI / charts / leaderboards). */
@Service
public class PlatformOverviewService {

  static final Duration EXPORT_TTL = Duration.ofHours(1);
  private static final int DEFAULT_TOP_N = 10;
  private static final int MAX_TOP_N = 50;

  private final PlatformOverviewStore store;
  private final AnalyticsExportPort exports;
  private final Clock clock;

  public PlatformOverviewService(
      PlatformOverviewStore store, AnalyticsExportPort exports, Clock clock) {
    this.store = store;
    this.exports = exports;
    this.clock = clock;
  }

  public Map<String, Object> overview(
      MedmatePrincipal principal, String period, String dateFrom, String dateTo) {
    requireOverview(principal);
    DateWindow window = PeriodResolver.resolveOverview(period, dateFrom, dateTo, clock);
    boolean aggregated = PeriodResolver.useAggregated(window);
    KpiTotals current = loadKpis(window, aggregated);
    KpiTotals prior = loadKpis(window.priorWindow(clock), aggregated);

    Map<String, Object> kpis = new LinkedHashMap<>();
    kpis.put(
        "gmv",
        moneyKpi(
            current.gmvPaise(), AnalyticsMath.wowDeltaPct(current.gmvPaise(), prior.gmvPaise())));
    kpis.put(
        "orders_count",
        countKpi(
            current.ordersCount(),
            AnalyticsMath.wowDeltaPct(current.ordersCount(), prior.ordersCount())));
    long aov = AnalyticsMath.aovPaise(current.gmvPaise(), current.ordersCount());
    long priorAov = AnalyticsMath.aovPaise(prior.gmvPaise(), prior.ordersCount());
    kpis.put("aov", moneyKpi(aov, AnalyticsMath.wowDeltaPct(aov, priorAov)));

    long net =
        AnalyticsMath.netRevenuePaise(
            current.gmvPaise(), current.refundsPaise(), current.cancellationsPaise());
    long priorNet =
        AnalyticsMath.netRevenuePaise(
            prior.gmvPaise(), prior.refundsPaise(), prior.cancellationsPaise());
    kpis.put("net_revenue", moneyKpi(net, AnalyticsMath.wowDeltaPct(net, priorNet)));

    BigDecimal margin = AnalyticsMath.netMarginPct(net, current.cogsEstimatePaise());
    BigDecimal priorMargin = AnalyticsMath.netMarginPct(priorNet, prior.cogsEstimatePaise());
    kpis.put(
        "net_margin_pct",
        pctKpi(margin, margin.subtract(priorMargin).setScale(1, java.math.RoundingMode.HALF_UP)));

    BigDecimal take = AnalyticsMath.takeRatePct(current.commissionPaise(), current.gmvPaise());
    BigDecimal priorTake = AnalyticsMath.takeRatePct(prior.commissionPaise(), prior.gmvPaise());
    kpis.put(
        "take_rate_pct",
        pctKpi(take, take.subtract(priorTake).setScale(1, java.math.RoundingMode.HALF_UP)));

    kpis.put(
        "active_customers",
        countKpi(
            current.activeCustomers(),
            AnalyticsMath.wowDeltaPct(current.activeCustomers(), prior.activeCustomers())));

    BigDecimal repeat =
        AnalyticsMath.repeatCustomerPct(current.repeatCustomers(), current.activeCustomers());
    BigDecimal priorRepeat =
        AnalyticsMath.repeatCustomerPct(prior.repeatCustomers(), prior.activeCustomers());
    kpis.put(
        "repeat_customer_pct",
        pctKpi(repeat, repeat.subtract(priorRepeat).setScale(1, java.math.RoundingMode.HALF_UP)));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("period", window.period());
    data.put("date_from", window.fromInclusive().toString());
    data.put("date_to", window.dateToDisplayInstant().toString());
    data.put("kpis", kpis);
    data.put("generated_at", clock.instant().toString());
    data.put("data_source", window.live() || !aggregated ? "LIVE" : "AGGREGATED");
    return data;
  }

  public Map<String, Object> charts(
      MedmatePrincipal principal, String period, String dateFrom, String dateTo) {
    requireOverview(principal);
    DateWindow window = PeriodResolver.resolveOverview(period, dateFrom, dateTo, clock);
    boolean aggregated = PeriodResolver.useAggregated(window);

    List<GmvTrendPoint> trend =
        aggregated
            ? store.aggregatedGmvTrend(window.fromDate(), window.toDate())
            : store.liveGmvTrend(window.fromInclusive(), window.toExclusive());
    List<Map<String, Object>> gmvTrend = fillMissingTrendDays(window, trend);

    List<CategoryMixRow> categories =
        aggregated
            ? store.aggregatedCategoryMix(window.fromDate(), window.toDate())
            : store.liveCategoryMix(window.fromInclusive(), window.toExclusive());
    long catTotal = categories.stream().mapToLong(CategoryMixRow::gmvPaise).sum();
    List<Map<String, Object>> categoryMix = new ArrayList<>();
    for (CategoryMixRow row : categories) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("category", row.category());
      m.put("gmv_paise", row.gmvPaise());
      m.put("pct", AnalyticsMath.ratioPct(row.gmvPaise(), catTotal));
      categoryMix.add(m);
    }

    List<PaymentMixRow> payments =
        aggregated
            ? store.aggregatedPaymentMix(window.fromDate(), window.toDate())
            : store.livePaymentMix(window.fromInclusive(), window.toExclusive());
    long payTotal = payments.stream().mapToLong(PaymentMixRow::ordersCount).sum();
    List<Map<String, Object>> paymentMix = new ArrayList<>();
    for (PaymentMixRow row : payments) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("method", row.method());
      m.put("orders", row.ordersCount());
      m.put("pct", AnalyticsMath.ratioPct(row.ordersCount(), payTotal));
      paymentMix.add(m);
    }

    List<ZoneSalesRow> zones =
        aggregated
            ? store.aggregatedSalesByZone(window.fromDate(), window.toDate())
            : store.liveSalesByZone(window.fromInclusive(), window.toExclusive());
    List<Map<String, Object>> salesByZone = new ArrayList<>();
    for (ZoneSalesRow row : zones) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("zone_id", row.zoneId() == null ? null : row.zoneId().toString());
      m.put("zone_name", row.zoneName());
      m.put("gmv_paise", row.gmvPaise());
      m.put("orders", row.ordersCount());
      salesByZone.add(m);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("gmv_trend", gmvTrend);
    data.put("category_mix", categoryMix);
    data.put("payment_mix", paymentMix);
    data.put("sales_by_zone", salesByZone);
    return data;
  }

  public Map<String, Object> leaderboards(
      MedmatePrincipal principal, String period, Integer topN, String export) {
    requireOverview(principal);
    DateWindow window = PeriodResolver.resolveLeaderboard(period, clock);
    int n = resolveTopN(topN, export);
    List<PharmacyLeader> pharmacies =
        store.topPharmacies(window.fromInclusive(), window.toExclusive(), n);
    List<RiderLeader> riders = store.topRiders(window.fromInclusive(), window.toExclusive(), n);

    List<Map<String, Object>> topPharmacies = new ArrayList<>();
    int rank = 1;
    for (PharmacyLeader p : pharmacies) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("rank", rank++);
      m.put("pharmacy_id", p.pharmacyId().toString());
      m.put("name", p.name());
      m.put("area", p.area());
      m.put("rating", p.rating());
      m.put("orders", p.orders());
      m.put("gmv_paise", p.gmvPaise());
      m.put("fill_rate_pct", p.fillRatePct());
      topPharmacies.add(m);
    }

    List<Map<String, Object>> topRiders = new ArrayList<>();
    rank = 1;
    for (RiderLeader r : riders) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("rank", rank++);
      m.put("rider_id", r.riderId().toString());
      m.put("name", r.name());
      m.put("zone", r.zone());
      m.put("trips", r.trips());
      m.put("on_time_pct", r.onTimePct());
      m.put("rating", r.rating());
      m.put("earnings_paise", r.earningsPaise());
      topRiders.add(m);
    }

    String exportUrl = null;
    if (export != null && !export.isBlank()) {
      String mode = export.trim().toLowerCase(Locale.ROOT);
      if (!"csv".equals(mode)) {
        throw new AppException("INVALID_PERIOD", "export must be csv when provided", 400);
      }
      String key = "analytics-leaderboards-" + UUID.randomUUID() + ".csv";
      byte[] csv = buildLeaderboardCsv(topPharmacies, topRiders);
      exports.put(key, csv, "text/csv");
      exportUrl = exports.signedGet(key, EXPORT_TTL).url();
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("top_pharmacies", topPharmacies);
    data.put("top_riders", topRiders);
    data.put("export_url", exportUrl);
    return data;
  }

  private KpiTotals loadKpis(DateWindow window, boolean aggregated) {
    if (aggregated) {
      return store.aggregatedKpis(window.fromDate(), window.toDate());
    }
    return store.liveKpis(window.fromInclusive(), window.toExclusive());
  }

  private static List<Map<String, Object>> fillMissingTrendDays(
      DateWindow window, List<GmvTrendPoint> points) {
    Map<LocalDate, Long> byDate = new LinkedHashMap<>();
    for (GmvTrendPoint p : points) {
      byDate.put(p.date(), p.gmvPaise());
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (LocalDate d = window.fromDate(); !d.isAfter(window.toDate()); d = d.plusDays(1)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("date", d.toString());
      row.put("gmv_paise", byDate.getOrDefault(d, 0L));
      out.add(row);
    }
    return out;
  }

  private static int resolveTopN(Integer topN, String export) {
    int n = topN == null ? DEFAULT_TOP_N : topN;
    if (n < 1) {
      throw new AppException("INVALID_PERIOD", "top_n must be between 1 and 50", 400);
    }
    boolean csv = export != null && "csv".equalsIgnoreCase(export.trim());
    if (n > MAX_TOP_N) {
      if (csv) {
        throw new AppException("EXPORT_TOO_LARGE", "top_n > 50 with export=csv", 422);
      }
      throw new AppException("INVALID_PERIOD", "top_n must be between 1 and 50", 400);
    }
    return n;
  }

  private static byte[] buildLeaderboardCsv(
      List<Map<String, Object>> pharmacies, List<Map<String, Object>> riders) {
    StringBuilder sb = new StringBuilder();
    sb.append("type,rank,id,name,area_or_zone,orders_or_trips,gmv_or_earnings_paise,rating\n");
    for (Map<String, Object> p : pharmacies) {
      sb.append("pharmacy,")
          .append(p.get("rank"))
          .append(',')
          .append(p.get("pharmacy_id"))
          .append(',')
          .append(csv(p.get("name")))
          .append(',')
          .append(csv(p.get("area")))
          .append(',')
          .append(p.get("orders"))
          .append(',')
          .append(p.get("gmv_paise"))
          .append(',')
          .append(p.get("rating"))
          .append('\n');
    }
    for (Map<String, Object> r : riders) {
      sb.append("rider,")
          .append(r.get("rank"))
          .append(',')
          .append(r.get("rider_id"))
          .append(',')
          .append(csv(r.get("name")))
          .append(',')
          .append(csv(r.get("zone")))
          .append(',')
          .append(r.get("trips"))
          .append(',')
          .append(r.get("earnings_paise"))
          .append(',')
          .append(r.get("rating"))
          .append('\n');
    }
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String csv(Object value) {
    if (value == null) {
      return "";
    }
    String s = String.valueOf(value);
    if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
      return '"' + s.replace("\"", "\"\"") + '"';
    }
    return s;
  }

  private static Map<String, Object> moneyKpi(long value, BigDecimal wow) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("value", value);
    m.put("unit", "paise");
    m.put("wow_delta_pct", wow);
    return m;
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

  static void requireOverview(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Overview analytics access required", 403);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Overview analytics access required", 403);
    }
  }
}

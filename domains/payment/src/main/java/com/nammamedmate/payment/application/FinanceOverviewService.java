package com.nammamedmate.payment.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.FinanceOverviewCachePort;
import com.nammamedmate.payment.application.port.out.FinanceOverviewQueryPort;
import com.nammamedmate.payment.application.port.out.FinanceOverviewQueryPort.ChartGranularity;
import com.nammamedmate.payment.application.port.out.FinanceOverviewQueryPort.ChartPoint;
import com.nammamedmate.payment.application.port.out.FinanceOverviewQueryPort.KpiSnapshot;
import com.nammamedmate.payment.application.port.out.FinanceOverviewQueryPort.PeriodTotals;
import com.nammamedmate.payment.domain.MoneyFormats;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/** EPIC-012 STORY-009 finance overview dashboard (read-only). */
@Service
public class FinanceOverviewService {

  static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final int MAX_CUSTOM_DAYS = 365;
  private static final Set<String> PERIODS = Set.of("TODAY", "7D", "30D", "90D", "CUSTOM");
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final FinanceOverviewQueryPort store;
  private final FinanceOverviewCachePort cache;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public FinanceOverviewService(
      FinanceOverviewQueryPort store,
      FinanceOverviewCachePort cache,
      ObjectMapper objectMapper,
      Clock clock) {
    this.store = store;
    this.cache = cache;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  public Map<String, Object> kpi(MedmatePrincipal principal) {
    requireFinance(principal);
    Optional<String> cached = cache.getKpiJson();
    if (cached.isPresent()) {
      try {
        return objectMapper.readValue(cached.get(), MAP_TYPE);
      } catch (Exception ignored) {
        // recompute on corrupt cache
      }
    }
    LocalDate today = LocalDate.now(clock.withZone(IST));
    Instant dayStart = today.atStartOfDay(IST).toInstant();
    Instant dayEnd = today.plusDays(1).atStartOfDay(IST).toInstant();
    KpiSnapshot snap = store.kpi(dayStart, dayEnd);
    Instant asOf = clock.instant();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("as_of", asOf.toString());
    data.put("gmv_today", MoneyFormats.paiseToRupees(snap.gmvTodayPaise()));
    data.put(
        "platform_revenue_today", MoneyFormats.paiseToRupees(snap.platformRevenueTodayPaise()));
    data.put("pharmacy_payout_due", MoneyFormats.paiseToRupees(snap.pharmacyPayoutDuePaise()));
    data.put("rider_payout_due", MoneyFormats.paiseToRupees(snap.riderPayoutDuePaise()));
    data.put("refunds_pending", snap.refundsPendingCount());
    data.put("refunds_pending_value", MoneyFormats.paiseToRupees(snap.refundsPendingValuePaise()));
    data.put("cod_in_hand", MoneyFormats.paiseToRupees(snap.codInHandPaise()));
    data.put(
        "active_wallet_balance_total", MoneyFormats.paiseToRupees(snap.activeWalletBalancePaise()));
    data.put("gateway_fees_today", MoneyFormats.paiseToRupees(snap.gatewayFeesTodayPaise()));
    try {
      cache.putKpiJson(objectMapper.writeValueAsString(data));
    } catch (Exception ignored) {
      // cache miss is fine
    }
    return data;
  }

  public Map<String, Object> pnl(
      MedmatePrincipal principal, String period, String from, String to) {
    requireFinance(principal);
    DateWindow window = resolvePeriod(period, from, to);
    PeriodTotals totals = store.periodTotals(window.fromInclusive(), window.toExclusive());
    long netRevenue = totals.commissionPaise() - totals.refundsPaise() - totals.gatewayFeesPaise();
    BigDecimal avgOrder =
        totals.ordersCount() == 0
            ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            : MoneyFormats.paiseToRupees(totals.gmvPaise())
                .divide(BigDecimal.valueOf(totals.ordersCount()), 2, RoundingMode.HALF_UP);

    ChartGranularity granularity =
        "TODAY".equals(window.period()) ? ChartGranularity.HOURLY : ChartGranularity.DAILY;
    List<ChartPoint> chart =
        store.gmvChart(window.fromInclusive(), window.toExclusive(), granularity);
    List<Map<String, Object>> chartOut = new ArrayList<>(chart.size());
    for (ChartPoint p : chart) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("label", p.label());
      row.put("gmv", MoneyFormats.paiseToRupees(p.gmvPaise()));
      row.put("orders", p.ordersCount());
      chartOut.add(row);
    }

    Map<String, Object> pie = new LinkedHashMap<>();
    pie.put("pharmacy_payout", MoneyFormats.paiseToRupees(totals.pharmacyPayoutPaise()));
    pie.put("platform_commission", MoneyFormats.paiseToRupees(totals.commissionPaise()));
    pie.put("tcs_collected", MoneyFormats.paiseToRupees(totals.tcsPaise()));
    pie.put("gateway_fees", MoneyFormats.paiseToRupees(totals.gatewayFeesPaise()));
    pie.put("refunds", MoneyFormats.paiseToRupees(totals.refundsPaise()));
    pie.put("net_platform_revenue", MoneyFormats.paiseToRupees(netRevenue));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("period", window.period());
    data.put("from", window.fromDate().toString());
    data.put("to", window.toDate().toString());
    data.put("gmv", MoneyFormats.paiseToRupees(totals.gmvPaise()));
    data.put("commission_earned", MoneyFormats.paiseToRupees(totals.commissionPaise()));
    data.put("refunds_issued", MoneyFormats.paiseToRupees(totals.refundsPaise()));
    data.put("gateway_fees", MoneyFormats.paiseToRupees(totals.gatewayFeesPaise()));
    data.put("net_revenue", MoneyFormats.paiseToRupees(netRevenue));
    data.put("orders_count", totals.ordersCount());
    data.put("avg_order_value", avgOrder);
    data.put("gmv_chart", chartOut);
    data.put("gmv_breakdown_pie", pie);
    return data;
  }

  public Map<String, Object> cashPosition(
      MedmatePrincipal principal, String period, String from, String to) {
    requireFinance(principal);
    DateWindow window = resolvePeriod(period, from, to);
    PeriodTotals totals = store.periodTotals(window.fromInclusive(), window.toExclusive());
    LocalDate today = LocalDate.now(clock.withZone(IST));
    Instant dayStart = today.atStartOfDay(IST).toInstant();
    Instant dayEnd = today.plusDays(1).atStartOfDay(IST).toInstant();
    KpiSnapshot live = store.kpi(dayStart, dayEnd);

    long received = totals.gmvPaise();
    long paidPharmacies = totals.pharmacyPayoutPaise();
    long paidRiders = totals.riderPayoutPaise();
    long refunded = totals.refundsPaise();
    // AC-004: platform_net excludes held_in_wallet
    long platformNet = received - paidPharmacies - paidRiders - refunded;
    long gatewayFees = totals.gatewayFeesPaise();
    long tcsHeld = totals.tcsPaise();
    long netFreeCash = platformNet - tcsHeld - gatewayFees;

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("period", window.period());
    data.put("from", window.fromDate().toString());
    data.put("to", window.toDate().toString());
    data.put("received_from_customers", MoneyFormats.paiseToRupees(received));
    data.put("paid_to_pharmacies", MoneyFormats.paiseToRupees(paidPharmacies));
    data.put("paid_to_riders", MoneyFormats.paiseToRupees(paidRiders));
    data.put("refunded_to_customers", MoneyFormats.paiseToRupees(refunded));
    data.put("held_in_wallet", MoneyFormats.paiseToRupees(live.activeWalletBalancePaise()));
    data.put("cod_in_transit", MoneyFormats.paiseToRupees(live.codInHandPaise()));
    data.put("platform_net", MoneyFormats.paiseToRupees(platformNet));
    data.put("gateway_fees_paid", MoneyFormats.paiseToRupees(gatewayFees));
    data.put("tcs_collected_held", MoneyFormats.paiseToRupees(tcsHeld));
    data.put("net_free_cash", MoneyFormats.paiseToRupees(netFreeCash));
    return data;
  }

  public Map<String, Object> ratios(
      MedmatePrincipal principal, String period, String from, String to) {
    requireFinance(principal);
    DateWindow window = resolvePeriod(period, from, to);
    PeriodTotals totals = store.periodTotals(window.fromInclusive(), window.toExclusive());
    long gmv = totals.gmvPaise();
    long totalPayouts = totals.pharmacyPayoutPaise() + totals.riderPayoutPaise();
    long netRevenue = totals.commissionPaise() - totals.refundsPaise() - totals.gatewayFeesPaise();

    LocalDate today = LocalDate.now(clock.withZone(IST));
    Instant thisWeekStart = today.minusDays(6).atStartOfDay(IST).toInstant();
    Instant thisWeekEnd = today.plusDays(1).atStartOfDay(IST).toInstant();
    Instant prevWeekStart = today.minusDays(13).atStartOfDay(IST).toInstant();
    Instant prevWeekEnd = today.minusDays(6).atStartOfDay(IST).toInstant();
    long thisWeekGmv = store.gmvSum(thisWeekStart, thisWeekEnd);
    long prevWeekGmv = store.gmvSum(prevWeekStart, prevWeekEnd);
    double thisAvg = thisWeekGmv / 7.0;
    double prevAvg = prevWeekGmv / 7.0;
    double changePct;
    if (prevAvg == 0.0) {
      changePct = thisAvg == 0.0 ? 0.0 : 100.0;
    } else {
      changePct = ((thisAvg - prevAvg) / prevAvg) * 100.0;
    }
    String trend;
    if (Math.abs(changePct) < 1.0) {
      trend = "FLAT";
    } else if (changePct > 0) {
      trend = "UP";
    } else {
      trend = "DOWN";
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("period", window.period());
    data.put("from", window.fromDate().toString());
    data.put("to", window.toDate().toString());
    data.put("take_rate_pct", pct(totals.commissionPaise(), gmv));
    data.put("payout_ratio_pct", pct(totalPayouts, gmv));
    data.put("refund_rate_pct", pct(totals.refundsPaise(), gmv));
    long codDenom =
        totals.capturedOrdersCount() > 0 ? totals.capturedOrdersCount() : totals.ordersCount();
    data.put("cod_share_pct", pct(totals.codOrdersCount(), codDenom));
    data.put("gateway_fee_rate_pct", pct(totals.gatewayFeesPaise(), gmv));
    data.put("net_revenue_margin_pct", pct(netRevenue, gmv));
    data.put("weekly_gmv_trend", trend);
    data.put(
        "weekly_gmv_change_pct", BigDecimal.valueOf(changePct).setScale(1, RoundingMode.HALF_UP));
    return data;
  }

  private DateWindow resolvePeriod(String periodRaw, String from, String to) {
    String period;
    if (periodRaw == null || periodRaw.isBlank()) {
      period = "7D";
    } else {
      period = periodRaw.trim().toUpperCase(Locale.ROOT);
    }
    if (!PERIODS.contains(period)) {
      throw new AppException("INVALID_PERIOD", "period not in allowed enum", 422);
    }
    LocalDate today = LocalDate.now(clock.withZone(IST));
    LocalDate fromDate;
    LocalDate toDate;
    if ("CUSTOM".equals(period)) {
      if (isBlank(from) || isBlank(to)) {
        throw new AppException("CUSTOM_DATES_REQUIRED", "period=CUSTOM requires from and to", 422);
      }
      fromDate = parseDate(from);
      toDate = parseDate(to);
      if (fromDate.isAfter(toDate)) {
        throw new AppException("INVALID_PERIOD", "from is after to", 422);
      }
      long days = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
      if (days > MAX_CUSTOM_DAYS) {
        throw new AppException("DATE_RANGE_TOO_LARGE", "CUSTOM range exceeds 365 days", 422);
      }
    } else if ("TODAY".equals(period)) {
      fromDate = today;
      toDate = today;
    } else {
      int days;
      if ("30D".equals(period)) {
        days = 30;
      } else if ("90D".equals(period)) {
        days = 90;
      } else {
        days = 7;
      }
      toDate = today;
      fromDate = today.minusDays(days);
    }
    Instant fromInclusive = fromDate.atStartOfDay(IST).toInstant();
    Instant toExclusive = toDate.plusDays(1).atStartOfDay(IST).toInstant();
    return new DateWindow(period, fromDate, toDate, fromInclusive, toExclusive);
  }

  private static BigDecimal pct(long numerator, long denominator) {
    if (denominator == 0L) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(numerator)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
  }

  private static LocalDate parseDate(String raw) {
    try {
      return LocalDate.parse(raw.trim());
    } catch (DateTimeParseException e) {
      throw new AppException("CUSTOM_DATES_REQUIRED", "from/to must be YYYY-MM-DD", 422);
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static void requireFinance(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Finance access required", 403);
    }
    AuthRole role = principal.role();
    boolean allowed = role == AuthRole.ADMIN_SUPER || role == AuthRole.ADMIN_FINANCE;
    if (!allowed) {
      throw new AppException("FORBIDDEN", "Finance access required", 403);
    }
  }

  private record DateWindow(
      String period,
      LocalDate fromDate,
      LocalDate toDate,
      Instant fromInclusive,
      Instant toExclusive) {}
}

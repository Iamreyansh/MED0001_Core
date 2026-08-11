package com.nammamedmate.analytics.application;

import com.nammamedmate.analytics.application.port.out.PlatformOpsStore;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.CancelPharmacyRow;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.CancelReasonRow;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.CancelSummary;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.CancelZoneRow;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.DeliverySegment;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.OpsTotals;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.PercentilePair;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.ZoneDeliveryRow;
import com.nammamedmate.analytics.domain.AnalyticsMath;
import com.nammamedmate.analytics.domain.PeriodResolver;
import com.nammamedmate.analytics.domain.PeriodResolver.DateWindow;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** EPIC-016 STORY-002 operations & SLA analytics. */
@Service
public class OperationsAnalyticsService {

  private static final List<String> FUNNEL_STAGES =
      List.of("orders_placed", "accepted", "packed", "out_for_delivery", "delivered");

  /** Client Command Center poll interval (AC-008); live_orders_now is always live for TODAY. */
  public static final int LIVE_ORDERS_REFRESH_SECONDS = 30;

  private final PlatformOpsStore store;
  private final Clock clock;

  public OperationsAnalyticsService(PlatformOpsStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public Map<String, Object> operations(
      MedmatePrincipal principal, String period, String dateFrom, String dateTo, String zoneIdRaw) {
    requireOps(principal);
    UUID zoneId = parseZone(zoneIdRaw);
    DateWindow window = PeriodResolver.resolveOverview(period, dateFrom, dateTo, clock);
    boolean live = window.live();
    OpsTotals current = loadOps(window, zoneId, live);
    OpsTotals prior = loadOps(window.priorWindow(clock), zoneId, live);

    BigDecimal sla =
        AnalyticsMath.ratioPct(
            current.ordersDelivered() - current.slaBreached(), current.ordersDelivered());
    BigDecimal priorSla =
        AnalyticsMath.ratioPct(
            prior.ordersDelivered() - prior.slaBreached(), prior.ordersDelivered());
    BigDecimal fill = AnalyticsMath.ratioPct(current.ordersPacked(), current.fillDenom());
    BigDecimal priorFill = AnalyticsMath.ratioPct(prior.ordersPacked(), prior.fillDenom());
    BigDecimal avgPrep =
        AnalyticsMath.avgMinutes(current.totalPrepSeconds(), current.ordersPacked());
    BigDecimal priorPrep = AnalyticsMath.avgMinutes(prior.totalPrepSeconds(), prior.ordersPacked());
    BigDecimal avgDel =
        AnalyticsMath.avgMinutes(current.totalDeliverySeconds(), current.ordersDelivered());
    BigDecimal priorDel =
        AnalyticsMath.avgMinutes(prior.totalDeliverySeconds(), prior.ordersDelivered());
    BigDecimal cancel = AnalyticsMath.ratioPct(current.ordersCancelled(), current.ordersPlaced());
    BigDecimal priorCancel = AnalyticsMath.ratioPct(prior.ordersCancelled(), prior.ordersPlaced());

    Map<String, Object> kpis = new LinkedHashMap<>();
    kpis.put(
        "sla_adherence_pct", pctKpi(sla, sla.subtract(priorSla).setScale(1, RoundingMode.HALF_UP)));
    kpis.put(
        "fill_rate_pct", pctKpi(fill, fill.subtract(priorFill).setScale(1, RoundingMode.HALF_UP)));
    kpis.put(
        "avg_prep_minutes",
        pctKpi(avgPrep, avgPrep.subtract(priorPrep).setScale(1, RoundingMode.HALF_UP)));
    kpis.put(
        "avg_delivery_minutes",
        pctKpi(avgDel, avgDel.subtract(priorDel).setScale(1, RoundingMode.HALF_UP)));
    kpis.put(
        "cancel_rate_pct",
        pctKpi(cancel, cancel.subtract(priorCancel).setScale(1, RoundingMode.HALF_UP)));

    Map<String, Object> liveOrders = new LinkedHashMap<>();
    if (live) {
      liveOrders.put("value", store.liveOrdersNow(zoneId));
      liveOrders.put("unit", "orders");
      liveOrders.put("refresh_seconds", LIVE_ORDERS_REFRESH_SECONDS);
    } else {
      liveOrders.put("value", null);
      liveOrders.put("unit", "orders");
    }
    kpis.put("live_orders_now", liveOrders);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("period", window.period());
    data.put("kpis", kpis);
    data.put("generated_at", clock.instant().toString());
    return data;
  }

  public Map<String, Object> fulfilmentFunnel(
      MedmatePrincipal principal, String period, String dateFrom, String dateTo, String zoneIdRaw) {
    requireOps(principal);
    UUID zoneId = parseZone(zoneIdRaw);
    DateWindow window = PeriodResolver.resolveOverview(period, dateFrom, dateTo, clock);
    OpsTotals totals = loadOps(window, zoneId, window.live());
    long[] counts = {
      totals.ordersPlaced(),
      totals.ordersAccepted(),
      totals.ordersPacked(),
      totals.ordersOutForDelivery(),
      totals.ordersDelivered()
    };
    List<Map<String, Object>> funnel = new ArrayList<>();
    Long prev = null;
    for (int i = 0; i < FUNNEL_STAGES.size(); i++) {
      Map<String, Object> stage = new LinkedHashMap<>();
      stage.put("stage", FUNNEL_STAGES.get(i));
      stage.put("count", counts[i]);
      stage.put("drop_off_pct", AnalyticsMath.dropOffPct(prev, counts[i]));
      funnel.add(stage);
      prev = counts[i];
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("period", window.period());
    data.put("funnel", funnel);
    data.put(
        "overall_completion_rate_pct",
        AnalyticsMath.ratioPct(totals.ordersDelivered(), totals.ordersPlaced()));
    return data;
  }

  public Map<String, Object> deliveryBreakdown(
      MedmatePrincipal principal, String period, String dateFrom, String dateTo) {
    requireOps(principal);
    DateWindow window = PeriodResolver.resolveOverview(period, dateFrom, dateTo, clock);
    DeliverySegment platform =
        store.liveDeliveryPlatform(window.fromInclusive(), window.toExclusive());
    List<ZoneDeliveryRow> zones =
        store.liveDeliveryByZone(window.fromInclusive(), window.toExclusive());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("period", window.period());
    data.put("platform_aggregate", segmentMap(platform));
    List<Map<String, Object>> byZone = new ArrayList<>();
    for (ZoneDeliveryRow z : zones) {
      Map<String, Object> row = new LinkedHashMap<>(segmentMap(z.segment()));
      row.put("zone_id", z.zoneId() == null ? null : z.zoneId().toString());
      row.put("zone_name", z.zoneName());
      row.put("sla_adherence_pct", z.slaAdherencePct());
      byZone.add(row);
    }
    data.put("by_zone", byZone);
    return data;
  }

  public Map<String, Object> cancellations(
      MedmatePrincipal principal, String period, String dateFrom, String dateTo, String zoneIdRaw) {
    requireOps(principal);
    UUID zoneId = parseZone(zoneIdRaw);
    DateWindow window = PeriodResolver.resolveOverview(period, dateFrom, dateTo, clock);
    CancelSummary summary =
        window.live()
            ? store.liveCancellations(window.fromInclusive(), window.toExclusive(), zoneId)
            : store.aggregatedCancellations(window.fromInclusive(), window.toExclusive(), zoneId);
    OpsTotals ops = loadOps(window, zoneId, window.live());

    Map<String, Object> summaryMap = new LinkedHashMap<>();
    summaryMap.put("total_cancellations", summary.totalCancellations());
    summaryMap.put(
        "cancel_rate_pct",
        AnalyticsMath.ratioPct(summary.totalCancellations(), ops.ordersPlaced()));

    long total = summary.totalCancellations();
    Map<String, Object> byStage = new LinkedHashMap<>();
    byStage.put(
        "pre_accept",
        Map.of(
            "count", summary.preAccept(),
            "pct", AnalyticsMath.ratioPct(summary.preAccept(), total)));
    byStage.put(
        "post_accept",
        Map.of(
            "count", summary.postAccept(),
            "pct", AnalyticsMath.ratioPct(summary.postAccept(), total)));

    List<Map<String, Object>> byReason = reasonRowsWithPctSum100(summary.byReason(), total);

    List<Map<String, Object>> topPharmacies = new ArrayList<>();
    for (CancelPharmacyRow p : summary.topPharmacies()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("pharmacy_id", p.pharmacyId().toString());
      m.put("name", p.name());
      m.put("cancellations", p.cancellations());
      m.put("cancel_rate_pct", AnalyticsMath.ratioPct(p.cancellations(), p.pharmacyOrders()));
      topPharmacies.add(m);
    }

    List<Map<String, Object>> byZone = new ArrayList<>();
    for (CancelZoneRow z : summary.byZone()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("zone_id", z.zoneId() == null ? null : z.zoneId().toString());
      m.put("zone_name", z.zoneName());
      m.put("cancellations", z.cancellations());
      m.put("cancel_rate_pct", AnalyticsMath.ratioPct(z.cancellations(), z.zoneOrders()));
      byZone.add(m);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("period", window.period());
    data.put("summary", summaryMap);
    data.put("by_stage", byStage);
    data.put("by_reason", byReason);
    data.put("top_pharmacies_by_cancellation", topPharmacies);
    data.put("by_zone", byZone);
    return data;
  }

  private OpsTotals loadOps(DateWindow window, UUID zoneId, boolean live) {
    if (live) {
      return store.liveOps(window.fromInclusive(), window.toExclusive(), zoneId);
    }
    return store.aggregatedOps(window.fromDate(), window.toDate(), zoneId);
  }

  private UUID parseZone(String zoneIdRaw) {
    if (zoneIdRaw == null || zoneIdRaw.isBlank()) {
      return null;
    }
    UUID zoneId;
    try {
      zoneId = UUID.fromString(zoneIdRaw.trim());
    } catch (IllegalArgumentException e) {
      throw new AppException("ZONE_NOT_FOUND", "zone_id does not exist", 404);
    }
    if (!store.zoneExists(zoneId)) {
      throw new AppException("ZONE_NOT_FOUND", "zone_id does not exist", 404);
    }
    return zoneId;
  }

  private static Map<String, Object> segmentMap(DeliverySegment s) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("pharmacy_prep_minutes", pairMap(s.pharmacyPrep()));
    m.put("rider_pickup_minutes", pairMap(s.riderPickup()));
    m.put("delivery_minutes", pairMap(s.delivery()));
    m.put("total_minutes", pairMap(s.total()));
    return m;
  }

  private static Map<String, Object> pairMap(PercentilePair p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("p50", p.p50());
    m.put("p90", p.p90());
    return m;
  }

  private static List<Map<String, Object>> reasonRowsWithPctSum100(
      List<CancelReasonRow> rows, long total) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (rows.isEmpty()) {
      return out;
    }
    BigDecimal assigned = BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    for (int i = 0; i < rows.size(); i++) {
      CancelReasonRow r = rows.get(i);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("reason", r.reason());
      m.put("actor", r.actor().toLowerCase(Locale.ROOT));
      m.put("count", r.count());
      BigDecimal pct;
      if (i == rows.size() - 1) {
        pct =
            BigDecimal.valueOf(100)
                .setScale(1, RoundingMode.HALF_UP)
                .subtract(assigned)
                .setScale(1, RoundingMode.HALF_UP);
        if (total == 0L) {
          pct = BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
      } else {
        pct = AnalyticsMath.ratioPct(r.count(), total);
        assigned = assigned.add(pct);
      }
      m.put("pct", pct);
      out.add(m);
    }
    return out;
  }

  private static Map<String, Object> pctKpi(BigDecimal value, BigDecimal wow) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("value", value);
    m.put("wow_delta_pct", wow);
    return m;
  }

  static void requireOps(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Operations analytics access required", 403);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Operations analytics access required", 403);
    }
  }
}

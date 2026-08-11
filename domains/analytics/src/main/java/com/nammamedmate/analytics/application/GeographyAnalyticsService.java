package com.nammamedmate.analytics.application;

import com.nammamedmate.analytics.application.port.out.GeographyDarkZoneOutboxPort;
import com.nammamedmate.analytics.application.port.out.PlatformGeographyStore;
import com.nammamedmate.analytics.application.port.out.PlatformGeographyStore.HourlyDemandCell;
import com.nammamedmate.analytics.application.port.out.PlatformGeographyStore.LiveRiderCount;
import com.nammamedmate.analytics.application.port.out.PlatformGeographyStore.ZoneMetrics;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** EPIC-016 STORY-005 geography analytics. */
@Service
public class GeographyAnalyticsService {

  public static final int HEATMAP_WINDOW_DAYS = 28;

  private final PlatformGeographyStore store;
  private final GeographyDarkZoneOutboxPort darkZoneOutbox;
  private final Clock clock;

  public GeographyAnalyticsService(
      PlatformGeographyStore store, GeographyDarkZoneOutboxPort darkZoneOutbox, Clock clock) {
    this.store = store;
    this.darkZoneOutbox = darkZoneOutbox;
    this.clock = clock;
  }

  public Map<String, Object> geography(
      MedmatePrincipal principal, String period, String sortRaw, String orderRaw) {
    requireOps(principal);
    DateWindow window = PeriodResolver.resolveGeography(period, clock);
    String sort = normalizeSort(sortRaw);
    boolean asc = "asc".equalsIgnoreCase(orderRaw == null ? "desc" : orderRaw.trim());

    List<ZoneMetrics> metrics =
        window.live()
            ? store.liveZoneMetrics(window.fromInclusive(), window.toExclusive())
            : store.aggregatedZoneMetrics(window.fromDate(), window.toDate());
    Map<UUID, Long> ridersByZone = ridersMap(store.liveRidersOnlineByZone());

    List<Map<String, Object>> zones = new ArrayList<>();
    int darkCount = 0;
    for (ZoneMetrics m : metrics) {
      long riders = ridersByZone.getOrDefault(m.zoneId(), 0L);
      boolean dark = riders == 0L;
      if (dark) {
        darkCount++;
        darkZoneOutbox.publishDarkZone(m.zoneId());
      }
      BigDecimal sla =
          m.orders() == 0L
              ? BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP)
              : AnalyticsMath.ratioPct(m.orders() - m.slaBreached(), m.orders());
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("zone_id", m.zoneId().toString());
      row.put("zone_name", m.zoneName());
      row.put("gmv_paise", m.gmvPaise());
      row.put("orders", m.orders());
      row.put("aov_paise", AnalyticsMath.aovPaise(m.gmvPaise(), m.orders()));
      row.put("pharmacies_count", m.pharmaciesCount());
      row.put("riders_online", riders);
      row.put("sla_adherence_pct", sla);
      row.put(
          "avg_delivery_minutes", AnalyticsMath.avgMinutes(m.totalDeliverySeconds(), m.orders()));
      row.put("is_dark", dark);
      zones.add(row);
    }
    zones.sort(comparator(sort, asc));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("period", window.period());
    data.put("zones", zones);
    data.put("total_zones", zones.size());
    data.put("dark_zones_count", darkCount);
    return data;
  }

  public Map<String, Object> supplyGap(
      MedmatePrincipal principal, String period, String severityFilterRaw) {
    requireOps(principal);
    DateWindow window = PeriodResolver.resolveGeographyGap(period, clock);
    String severityFilter =
        severityFilterRaw == null || severityFilterRaw.isBlank()
            ? null
            : severityFilterRaw.trim().toUpperCase(Locale.ROOT);

    List<ZoneMetrics> metrics = store.aggregatedZoneMetrics(window.fromDate(), window.toDate());
    Map<UUID, Long> ridersByZone = ridersMap(store.liveRidersOnlineByZone());
    long days = window.dayCount();

    int critical = 0;
    int high = 0;
    int moderate = 0;
    int low = 0;
    List<Map<String, Object>> zones = new ArrayList<>();

    for (ZoneMetrics m : metrics) {
      long ridersLive = ridersByZone.getOrDefault(m.zoneId(), 0L);
      boolean dark = ridersLive == 0L;
      BigDecimal demand = AnalyticsMath.demandScore(m.orders(), days);
      BigDecimal supply = AnalyticsMath.supplyScore(m.avgRidersOnline(), m.pharmacyCoveragePct());
      BigDecimal gap = AnalyticsMath.gapPct(demand, supply);
      String severity = AnalyticsMath.gapSeverity(gap, dark);
      switch (severity) {
        case "CRITICAL" -> critical++;
        case "HIGH" -> high++;
        case "MODERATE" -> moderate++;
        default -> low++;
      }
      if (severityFilter != null && !severityFilter.equals(severity)) {
        continue;
      }
      String suggestion =
          AnalyticsMath.supplyGapSuggestion(
              severity, m.pharmacyCoveragePct(), m.unservedAttempts());
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("zone_id", m.zoneId().toString());
      row.put("zone_name", m.zoneName());
      row.put("demand_score", demand);
      row.put("supply_score", supply);
      row.put("gap_pct", gap);
      row.put("gap_severity", severity);
      row.put(
          "pharmacy_coverage_pct",
          m.pharmacyCoveragePct() == null
              ? BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP)
              : m.pharmacyCoveragePct().setScale(1, RoundingMode.HALF_UP));
      row.put("suggestion", suggestion);
      row.put(
          "current_riders_avg",
          m.avgRidersOnline() == null
              ? BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP)
              : m.avgRidersOnline().setScale(1, RoundingMode.HALF_UP));
      row.put("current_pharmacies", m.pharmaciesCount());
      zones.add(row);
    }

    zones.sort(
        Comparator.comparing(
                (Map<String, Object> z) -> severityRank((String) z.get("gap_severity")))
            .thenComparing(
                z -> (BigDecimal) z.get("gap_pct"),
                Comparator.nullsLast(Comparator.reverseOrder())));

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("critical_zones", critical);
    summary.put("high_zones", high);
    summary.put("moderate_zones", moderate);
    summary.put("low_zones", low);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("period", window.period());
    data.put("summary", summary);
    data.put("zones", zones);
    return data;
  }

  public Map<String, Object> demandHeatmap(MedmatePrincipal principal, String zoneIdRaw) {
    requireOps(principal);
    UUID zoneId = null;
    if (zoneIdRaw != null && !zoneIdRaw.isBlank()) {
      try {
        zoneId = UUID.fromString(zoneIdRaw.trim());
      } catch (IllegalArgumentException e) {
        throw new AppException("ZONE_NOT_FOUND", "zone_id does not exist", 404);
      }
      if (!store.zoneExists(zoneId)) {
        throw new AppException("ZONE_NOT_FOUND", "zone_id does not exist", 404);
      }
    }

    // AC-008: read precomputed cells only — never live-scan orders.
    List<HourlyDemandCell> cells = store.heatmapCells(zoneId);
    if (zoneId != null && cells.isEmpty()) {
      // Zone exists but heatmap not yet computed — still return 24 zero hours (AC-004).
      Map<String, Object> zone = new LinkedHashMap<>();
      zone.put("zone_id", zoneId.toString());
      zone.put("zone_name", null);
      zone.put("hourly", emptyHourly());
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("computed_over_days", HEATMAP_WINDOW_DAYS);
      data.put("zones", List.of(zone));
      return data;
    }

    Map<UUID, String> names = new LinkedHashMap<>();
    Map<UUID, Map<Integer, List<HourlyDemandCell>>> byZoneHour = new LinkedHashMap<>();
    for (HourlyDemandCell c : cells) {
      names.putIfAbsent(c.zoneId(), c.zoneName());
      byZoneHour
          .computeIfAbsent(c.zoneId(), k -> new HashMap<>())
          .computeIfAbsent(c.hourOfDay(), k -> new ArrayList<>())
          .add(c);
    }

    List<Map<String, Object>> zones = new ArrayList<>();
    for (Map.Entry<UUID, Map<Integer, List<HourlyDemandCell>>> e : byZoneHour.entrySet()) {
      List<Map<String, Object>> hourly = buildHourly(e.getValue());
      Map<String, Object> zone = new LinkedHashMap<>();
      zone.put("zone_id", e.getKey().toString());
      zone.put("zone_name", names.get(e.getKey()));
      zone.put("hourly", hourly);
      zones.add(zone);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("computed_over_days", HEATMAP_WINDOW_DAYS);
    data.put("zones", zones);
    return data;
  }

  private static List<Map<String, Object>> emptyHourly() {
    return buildHourly(Map.of());
  }

  private static List<Map<String, Object>> buildHourly(
      Map<Integer, List<HourlyDemandCell>> byHour) {
    List<Map<String, Object>> hourly = new ArrayList<>();
    for (int hour = 0; hour < 24; hour++) {
      List<HourlyDemandCell> hourCells = byHour.getOrDefault(hour, List.of());
      BigDecimal avg = BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
      String peakDay = null;
      BigDecimal peakAvg = BigDecimal.valueOf(-1);
      if (!hourCells.isEmpty()) {
        BigDecimal sum = BigDecimal.ZERO;
        for (HourlyDemandCell c : hourCells) {
          BigDecimal a = c.avgOrders() == null ? BigDecimal.ZERO : c.avgOrders();
          sum = sum.add(a);
          if (a.compareTo(peakAvg) > 0) {
            peakAvg = a;
            peakDay = AnalyticsMath.dayOfWeekName(c.dayOfWeek());
          }
        }
        avg = sum.divide(BigDecimal.valueOf(hourCells.size()), 1, RoundingMode.HALF_UP);
        if (peakAvg.compareTo(BigDecimal.valueOf(0.05)) < 0) {
          peakDay = null;
        }
      }
      Map<String, Object> h = new LinkedHashMap<>();
      h.put("hour_of_day", hour);
      h.put("avg_orders", avg);
      h.put("peak_day", peakDay);
      hourly.add(h);
    }
    return hourly;
  }

  static void requireOps(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("FORBIDDEN", "Geography analytics access required", 403);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Geography analytics access required", 403);
    }
  }

  private static Map<UUID, Long> ridersMap(List<LiveRiderCount> rows) {
    Map<UUID, Long> map = new HashMap<>();
    for (LiveRiderCount r : rows) {
      map.put(r.zoneId(), r.ridersOnline());
    }
    return map;
  }

  private static String normalizeSort(String sortRaw) {
    if (sortRaw == null || sortRaw.isBlank()) {
      return "gmv";
    }
    String sort = sortRaw.trim().toLowerCase(Locale.ROOT);
    return switch (sort) {
      case "gmv", "orders", "sla_adherence_pct", "avg_delivery_minutes" -> sort;
      default -> "gmv";
    };
  }

  private static Comparator<Map<String, Object>> comparator(String sort, boolean asc) {
    Comparator<Map<String, Object>> cmp =
        switch (sort) {
          case "orders" -> Comparator.comparingLong(z -> ((Number) z.get("orders")).longValue());
          case "sla_adherence_pct" ->
              Comparator.comparing(z -> (BigDecimal) z.get("sla_adherence_pct"));
          case "avg_delivery_minutes" ->
              Comparator.comparing(z -> (BigDecimal) z.get("avg_delivery_minutes"));
          default -> Comparator.comparingLong(z -> ((Number) z.get("gmv_paise")).longValue());
        };
    return asc ? cmp : cmp.reversed();
  }

  private static int severityRank(String severity) {
    return switch (severity) {
      case "CRITICAL" -> 0;
      case "HIGH" -> 1;
      case "MODERATE" -> 2;
      default -> 3;
    };
  }
}

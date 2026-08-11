package com.nammamedmate.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.port.out.GeographyDarkZoneOutboxPort;
import com.nammamedmate.analytics.application.port.out.PlatformGeographyStore;
import com.nammamedmate.analytics.application.port.out.PlatformGeographyStore.HourlyDemandCell;
import com.nammamedmate.analytics.application.port.out.PlatformGeographyStore.LiveRiderCount;
import com.nammamedmate.analytics.application.port.out.PlatformGeographyStore.ZoneMetrics;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GeographyAnalyticsCoverageTest {

  @Mock private PlatformGeographyStore store;
  @Mock private GeographyDarkZoneOutboxPort darkOutbox;

  private GeographyAnalyticsService service;
  private MedmatePrincipal superAdmin;

  @BeforeEach
  void setUp() {
    service =
        new GeographyAnalyticsService(
            store, darkOutbox, Clock.fixed(Instant.parse("2026-07-24T16:00:00Z"), ZoneOffset.UTC));
    superAdmin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  }

  @Test
  @SuppressWarnings("unchecked")
  void todayLivePathSortOrdersAscAndExpandZoneSuggestion() {
    UUID z = UUID.randomUUID();
    when(store.liveZoneMetrics(any(), any()))
        .thenReturn(
            List.of(
                new ZoneMetrics(z, "Z", 0, 0, 0, 0, BigDecimal.ZERO, 1, bd("90"), 3),
                new ZoneMetrics(UUID.randomUUID(), "Y", 10, 5, 1, 300, bd("2"), 2, bd("90"), 0)));
    when(store.liveRidersOnlineByZone()).thenReturn(List.of(new LiveRiderCount(z, 1L)));

    Map<String, Object> geo = service.geography(superAdmin, "TODAY", "orders", "asc");
    List<Map<String, Object>> zones = (List<Map<String, Object>>) geo.get("zones");
    assertThat(((Number) zones.get(0).get("orders")).longValue())
        .isLessThanOrEqualTo(((Number) zones.get(1).get("orders")).longValue());

    when(store.aggregatedZoneMetrics(any(), any()))
        .thenReturn(List.of(new ZoneMetrics(z, "Z", 0, 50, 0, 0, bd("4"), 1, bd("90"), 5)));
    when(store.liveRidersOnlineByZone()).thenReturn(List.of(new LiveRiderCount(z, 2L)));
    Map<String, Object> gap = service.supplyGap(superAdmin, "30D", "LOW");
    List<Map<String, Object>> gapZones = (List<Map<String, Object>>) gap.get("zones");
    assertThat(gapZones).isNotEmpty();
    assertThat(gapZones.getFirst().get("suggestion")).isEqualTo("EXPAND_ZONE");
  }

  @Test
  void zoneNotFoundAndNullPrincipalAndSortFallbacks() {
    assertThatThrownBy(() -> service.demandHeatmap(superAdmin, "not-a-uuid"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ZONE_NOT_FOUND");
    UUID missing = UUID.randomUUID();
    when(store.zoneExists(missing)).thenReturn(false);
    assertThatThrownBy(() -> service.demandHeatmap(superAdmin, missing.toString()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ZONE_NOT_FOUND");

    assertThatThrownBy(() -> GeographyAnalyticsService.requireOps(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    when(store.liveZoneMetrics(any(), any())).thenReturn(List.of());
    when(store.liveRidersOnlineByZone()).thenReturn(List.of());
    assertThat(service.geography(superAdmin, "TODAY", "bogus", null).get("total_zones"))
        .isEqualTo(0);

    when(store.heatmapCells(isNull())).thenReturn(List.of());
    assertThat(service.demandHeatmap(superAdmin, null).get("zones")).isEqualTo(List.of());
  }

  @Test
  void refreshServiceDelegates() {
    PlatformGeographyStore s = store;
    GeographyAnalyticsRefreshService refresh =
        new GeographyAnalyticsRefreshService(
            s, Clock.fixed(Instant.parse("2026-07-24T16:00:00Z"), ZoneOffset.UTC));
    refresh.refreshYesterdayAndHeatmap();
    verify(s).refreshZoneDaily(LocalDate.of(2026, 7, 23), LocalDate.of(2026, 7, 23));
    verify(s).refreshHourlyDemand(LocalDate.of(2026, 7, 24), 28);
    refresh.refreshRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2));
    verify(s).refreshZoneDaily(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2));
  }

  @Test
  @SuppressWarnings("unchecked")
  void slaAndDeliverySortAndSeverityFilterEmpty() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(store.aggregatedZoneMetrics(any(), any()))
        .thenReturn(
            List.of(
                new ZoneMetrics(a, "A", 100, 10, 0, 600, bd("1"), 1, bd("50"), 0),
                new ZoneMetrics(b, "B", 200, 20, 10, 1200, bd("1"), 1, bd("50"), 0)));
    when(store.liveRidersOnlineByZone())
        .thenReturn(List.of(new LiveRiderCount(a, 1L), new LiveRiderCount(b, 1L)));

    List<Map<String, Object>> bySla =
        (List<Map<String, Object>>)
            service.geography(superAdmin, "7D", "sla_adherence_pct", "desc").get("zones");
    assertThat((BigDecimal) bySla.get(0).get("sla_adherence_pct"))
        .isGreaterThanOrEqualTo((BigDecimal) bySla.get(1).get("sla_adherence_pct"));

    List<Map<String, Object>> byDel =
        (List<Map<String, Object>>)
            service.geography(superAdmin, "7D", "avg_delivery_minutes", "asc").get("zones");
    assertThat((BigDecimal) byDel.get(0).get("avg_delivery_minutes"))
        .isLessThanOrEqualTo((BigDecimal) byDel.get(1).get("avg_delivery_minutes"));

    Map<String, Object> filtered = service.supplyGap(superAdmin, "7D", "CRITICAL");
    assertThat((List<?>) filtered.get("zones")).isEmpty();
  }

  @Test
  void heatmapPeakDayAndNullAvgOrders() {
    UUID z = UUID.randomUUID();
    when(store.heatmapCells(isNull()))
        .thenReturn(
            List.of(
                new HourlyDemandCell(z, "Z", 12, 0, null),
                new HourlyDemandCell(z, "Z", 12, 1, bd("0.01"))));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> zones =
        (List<Map<String, Object>>) service.demandHeatmap(superAdmin, null).get("zones");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> hourly = (List<Map<String, Object>>) zones.getFirst().get("hourly");
    assertThat(hourly.get(12).get("peak_day")).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void nullCoverageRidersBlankFiltersAndHeatmapWithCells() {
    UUID z = UUID.randomUUID();
    when(store.aggregatedZoneMetrics(any(), any()))
        .thenReturn(List.of(new ZoneMetrics(z, "N", 0, 10, 0, 0, null, 1, null, 0)));
    when(store.liveRidersOnlineByZone()).thenReturn(List.of(new LiveRiderCount(z, 1L)));

    Map<String, Object> gap = service.supplyGap(superAdmin, "7D", "   ");
    List<Map<String, Object>> zones = (List<Map<String, Object>>) gap.get("zones");
    assertThat((BigDecimal) zones.getFirst().get("pharmacy_coverage_pct"))
        .isEqualByComparingTo(BigDecimal.ZERO.setScale(1));
    assertThat((BigDecimal) zones.getFirst().get("current_riders_avg"))
        .isEqualByComparingTo(BigDecimal.ZERO.setScale(1));

    when(store.liveZoneMetrics(any(), any())).thenReturn(List.of());
    when(store.liveRidersOnlineByZone()).thenReturn(List.of());
    assertThat(service.geography(superAdmin, "TODAY", "  ", "desc").get("total_zones"))
        .isEqualTo(0);

    when(store.zoneExists(z)).thenReturn(true);
    when(store.heatmapCells(z)).thenReturn(List.of(new HourlyDemandCell(z, "N", 7, 1, bd("4.8"))));
    Map<String, Object> heat = service.demandHeatmap(superAdmin, "  " + z + "  ");
    assertThat((List<?>) heat.get("zones")).hasSize(1);

    when(store.heatmapCells(isNull())).thenReturn(List.of());
    assertThat(service.demandHeatmap(superAdmin, "   ").get("zones")).isEqualTo(List.of());
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }
}

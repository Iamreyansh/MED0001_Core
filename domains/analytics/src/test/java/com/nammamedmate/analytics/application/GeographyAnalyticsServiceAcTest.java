package com.nammamedmate.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
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
class GeographyAnalyticsServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T16:00:00Z");

  @Mock private PlatformGeographyStore store;
  @Mock private GeographyDarkZoneOutboxPort darkOutbox;

  private GeographyAnalyticsService service;
  private MedmatePrincipal ops;
  private MedmatePrincipal support;

  private final UUID zoneHigh = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private final UUID zoneDark = UUID.fromString("a0000002-0000-4000-8000-000000000002");

  @BeforeEach
  void setUp() {
    service = new GeographyAnalyticsService(store, darkOutbox, Clock.fixed(NOW, ZoneOffset.UTC));
    ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    support =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac001_geographySortedByGmvDescAndDarkWhenNoRiders() {
    when(store.aggregatedZoneMetrics(any(), any()))
        .thenReturn(
            List.of(
                metrics(
                    zoneHigh,
                    "Indiranagar",
                    980_000,
                    820,
                    40,
                    820 * 28 * 60L,
                    bd("12.0"),
                    8,
                    bd("90"),
                    0),
                metrics(
                    zoneDark,
                    "Whitefield",
                    312_000,
                    280,
                    100,
                    280 * 68 * 60L,
                    bd("0.0"),
                    3,
                    bd("88"),
                    0)));
    when(store.liveRidersOnlineByZone()).thenReturn(List.of(new LiveRiderCount(zoneHigh, 12L)));

    Map<String, Object> data = service.geography(ops, "7D", null, null);

    List<Map<String, Object>> zones = (List<Map<String, Object>>) data.get("zones");
    assertThat(zones).hasSize(2);
    assertThat(zones.get(0).get("zone_name")).isEqualTo("Indiranagar");
    assertThat(zones.get(0).get("is_dark")).isEqualTo(false);
    assertThat(zones.get(1).get("zone_name")).isEqualTo("Whitefield");
    assertThat(zones.get(1).get("is_dark")).isEqualTo(true);
    assertThat(zones.get(1).get("riders_online")).isEqualTo(0L);
    verify(darkOutbox).publishDarkZone(zoneDark);
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac002_supplyGapSeverityBands() {
    // 7D window dayCount=8 at fixed clock → demand = orders / 192
    UUID c = UUID.randomUUID();
    UUID h = UUID.randomUUID();
    UUID m = UUID.randomUUID();
    UUID l = UUID.randomUUID();
    when(store.aggregatedZoneMetrics(any(), any()))
        .thenReturn(
            List.of(
                metrics(c, "C", 0, 2000, 0, 0, bd("1.0"), 1, bd("90"), 0),
                metrics(h, "H", 0, 1000, 0, 0, bd("3.0"), 1, bd("90"), 0),
                metrics(m, "M", 0, 500, 0, 0, bd("2.2"), 1, bd("90"), 0),
                metrics(l, "L", 0, 100, 0, 0, bd("10.0"), 1, bd("90"), 0)));
    when(store.liveRidersOnlineByZone())
        .thenReturn(
            List.of(
                new LiveRiderCount(c, 2L),
                new LiveRiderCount(h, 2L),
                new LiveRiderCount(m, 2L),
                new LiveRiderCount(l, 2L)));

    Map<String, Object> data = service.supplyGap(ops, "7D", null);
    List<Map<String, Object>> zones = (List<Map<String, Object>>) data.get("zones");
    Map<String, String> byName = new java.util.HashMap<>();
    for (Map<String, Object> z : zones) {
      byName.put((String) z.get("zone_name"), (String) z.get("gap_severity"));
    }
    assertThat(byName.get("C")).isEqualTo("CRITICAL");
    assertThat(byName.get("H")).isEqualTo("HIGH");
    assertThat(byName.get("M")).isEqualTo("MODERATE");
    assertThat(byName.get("L")).isEqualTo("LOW");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac003_suggestionsAddRidersAndAddPharmacies() {
    UUID riders = UUID.randomUUID();
    UUID pharm = UUID.randomUUID();
    when(store.aggregatedZoneMetrics(any(), any()))
        .thenReturn(
            List.of(
                metrics(riders, "Riders", 0, 2000, 0, 0, bd("1.0"), 3, bd("88"), 0),
                metrics(pharm, "Pharm", 0, 1000, 0, 0, bd("5.0"), 2, bd("45"), 0)));
    when(store.liveRidersOnlineByZone())
        .thenReturn(List.of(new LiveRiderCount(riders, 2L), new LiveRiderCount(pharm, 2L)));

    Map<String, Object> data = service.supplyGap(ops, "7D", null);
    List<Map<String, Object>> zones = (List<Map<String, Object>>) data.get("zones");
    Map<String, String> suggestions = new java.util.HashMap<>();
    for (Map<String, Object> z : zones) {
      suggestions.put((String) z.get("zone_name"), (String) z.get("suggestion"));
    }
    assertThat(suggestions.get("Riders")).isEqualTo("ADD_RIDERS");
    assertThat(suggestions.get("Pharm")).isEqualTo("ADD_PHARMACIES");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac004_heatmapReturns24HoursPerZone() {
    when(store.heatmapCells(isNull()))
        .thenReturn(
            List.of(
                new HourlyDemandCell(zoneHigh, "Indiranagar", 8, 1, bd("8.2")),
                new HourlyDemandCell(zoneHigh, "Indiranagar", 8, 2, bd("7.0")),
                new HourlyDemandCell(zoneHigh, "Indiranagar", 20, 6, bd("11.2"))));

    Map<String, Object> data = service.demandHeatmap(ops, null);
    List<Map<String, Object>> zones = (List<Map<String, Object>>) data.get("zones");
    List<Map<String, Object>> hourly = (List<Map<String, Object>>) zones.getFirst().get("hourly");
    assertThat(hourly).hasSize(24);
    assertThat(hourly.get(0).get("hour_of_day")).isEqualTo(0);
    assertThat(hourly.get(23).get("hour_of_day")).isEqualTo(23);
    assertThat(data.get("computed_over_days")).isEqualTo(28);
  }

  @Test
  void ac005_periodOver30dInvalid() {
    assertThatThrownBy(() -> service.geography(ops, "90D", null, null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");
    assertThatThrownBy(() -> service.supplyGap(ops, "TODAY", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac006_darkZonesForceCriticalInSupplyGap() {
    when(store.aggregatedZoneMetrics(any(), any()))
        .thenReturn(
            List.of(metrics(zoneDark, "Whitefield", 0, 10, 0, 0, bd("10.0"), 3, bd("88"), 0)));
    when(store.liveRidersOnlineByZone()).thenReturn(List.of()); // no riders → dark

    Map<String, Object> data = service.supplyGap(ops, "7D", null);
    List<Map<String, Object>> zones = (List<Map<String, Object>>) data.get("zones");
    assertThat(zones.getFirst().get("gap_severity")).isEqualTo("CRITICAL");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac007_darkZonesCountMatchesIsDarkFlags() {
    when(store.aggregatedZoneMetrics(any(), any()))
        .thenReturn(
            List.of(
                metrics(zoneHigh, "A", 100, 10, 0, 0, bd("1"), 1, bd("80"), 0),
                metrics(zoneDark, "B", 50, 5, 0, 0, bd("0"), 1, bd("80"), 0)));
    when(store.liveRidersOnlineByZone()).thenReturn(List.of(new LiveRiderCount(zoneHigh, 1L)));

    Map<String, Object> data = service.geography(ops, "30D", "gmv", "desc");
    List<Map<String, Object>> zones = (List<Map<String, Object>>) data.get("zones");
    long flagged = zones.stream().filter(z -> Boolean.TRUE.equals(z.get("is_dark"))).count();
    assertThat(data.get("dark_zones_count")).isEqualTo((int) flagged).isEqualTo(1);
  }

  @Test
  void ac008_heatmapNeverLiveScansOrders() {
    when(store.zoneExists(zoneHigh)).thenReturn(true);
    when(store.heatmapCells(eq(zoneHigh))).thenReturn(List.of());

    service.demandHeatmap(ops, zoneHigh.toString());

    verify(store).heatmapCells(zoneHigh);
    verify(store, never()).liveZoneMetrics(any(), any());
    verify(store, never()).refreshHourlyDemand(any(), any(Integer.class));
  }

  @Test
  void forbiddenForSupport() {
    assertThatThrownBy(() -> service.geography(support, "7D", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  private static ZoneMetrics metrics(
      UUID id,
      String name,
      long gmv,
      long orders,
      long slaBreached,
      long deliverySeconds,
      BigDecimal riders,
      int pharmacies,
      BigDecimal coverage,
      int unserved) {
    return new ZoneMetrics(
        id,
        name,
        gmv,
        orders,
        slaBreached,
        deliverySeconds,
        riders,
        pharmacies,
        coverage,
        unserved);
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }
}

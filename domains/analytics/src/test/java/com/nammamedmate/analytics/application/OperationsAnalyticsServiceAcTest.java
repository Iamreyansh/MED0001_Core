package com.nammamedmate.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.port.out.PlatformOpsStore;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.CancelPharmacyRow;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.CancelReasonRow;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.CancelSummary;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.CancelZoneRow;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.DeliverySegment;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.OpsTotals;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.PercentilePair;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.ZoneDeliveryRow;
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
class OperationsAnalyticsServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T16:00:00Z");

  @Mock private PlatformOpsStore store;

  private OperationsAnalyticsService service;
  private MedmatePrincipal ops;
  private MedmatePrincipal support;

  @BeforeEach
  void setUp() {
    service = new OperationsAnalyticsService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    support =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac001_todayReturnsLiveOrdersNow() {
    when(store.liveOps(any(), any(), isNull()))
        .thenReturn(sampleOps(100, 90, 80, 75, 70, 5, 2, 95, 3));
    when(store.liveOrdersNow(isNull())).thenReturn(47L);

    Map<String, Object> data = service.operations(ops, "TODAY", null, null, null);

    Map<String, Object> kpis = (Map<String, Object>) data.get("kpis");
    Map<String, Object> live = (Map<String, Object>) kpis.get("live_orders_now");
    assertThat(live.get("value")).isEqualTo(47L);
    assertThat(live.get("unit")).isEqualTo("orders");
    assertThat(live.get("refresh_seconds"))
        .isEqualTo(OperationsAnalyticsService.LIVE_ORDERS_REFRESH_SECONDS);
    verify(store).liveOrdersNow(isNull());
    verify(store, never()).aggregatedOps(any(), any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac002_funnelHasFiveStagesWithNullDropOffOnPlaced() {
    when(store.liveOps(any(), any(), isNull()))
        .thenReturn(sampleOps(3841, 3726, 3534, 3498, 3420, 100, 50, 3791, 10));

    Map<String, Object> data = service.fulfilmentFunnel(ops, "TODAY", null, null, null);

    List<Map<String, Object>> funnel = (List<Map<String, Object>>) data.get("funnel");
    assertThat(funnel).hasSize(5);
    assertThat(funnel.get(0).get("stage")).isEqualTo("orders_placed");
    assertThat(funnel.get(0).get("drop_off_pct")).isNull();
    assertThat(funnel.get(1).get("stage")).isEqualTo("accepted");
    assertThat(funnel.get(2).get("stage")).isEqualTo("packed");
    assertThat(funnel.get(3).get("stage")).isEqualTo("out_for_delivery");
    assertThat(funnel.get(4).get("stage")).isEqualTo("delivered");
    assertThat(funnel.get(1).get("drop_off_pct")).isNotNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac003_deliveryBreakdownReturnsP50P90PerSegmentAndZone() {
    PercentilePair pair = new PercentilePair(bd("7.5"), bd("14.2"));
    DeliverySegment segment = new DeliverySegment(pair, pair, pair, pair);
    UUID zoneId = UUID.randomUUID();
    when(store.liveDeliveryPlatform(any(), any())).thenReturn(segment);
    when(store.liveDeliveryByZone(any(), any()))
        .thenReturn(List.of(new ZoneDeliveryRow(zoneId, "Indiranagar", segment, bd("95.1"))));

    Map<String, Object> data = service.deliveryBreakdown(ops, "7D", null, null);

    Map<String, Object> platform = (Map<String, Object>) data.get("platform_aggregate");
    assertThat(platform)
        .containsKeys(
            "pharmacy_prep_minutes", "rider_pickup_minutes", "delivery_minutes", "total_minutes");
    for (String key :
        List.of(
            "pharmacy_prep_minutes", "rider_pickup_minutes", "delivery_minutes", "total_minutes")) {
      Map<String, Object> prep = (Map<String, Object>) platform.get(key);
      assertThat(prep).containsKeys("p50", "p90");
    }
    List<Map<String, Object>> byZone = (List<Map<String, Object>>) data.get("by_zone");
    Map<String, Object> zoneRow = byZone.getFirst();
    assertThat(zoneRow)
        .containsKeys(
            "zone_id",
            "zone_name",
            "pharmacy_prep_minutes",
            "rider_pickup_minutes",
            "delivery_minutes",
            "total_minutes",
            "sla_adherence_pct");
    assertThat((Map<String, Object>) zoneRow.get("total_minutes")).containsKeys("p50", "p90");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac004_cancellationsGroupActorsAndPctSum100() {
    when(store.liveOps(any(), any(), isNull()))
        .thenReturn(sampleOps(1000, 900, 800, 780, 750, 100, 40, 960, 5));
    when(store.liveCancellations(any(), any(), isNull()))
        .thenReturn(
            new CancelSummary(
                100,
                40,
                60,
                List.of(
                    new CancelReasonRow("out_of_stock", "PHARMACY", 40),
                    new CancelReasonRow("changed_mind", "CUSTOMER", 35),
                    new CancelReasonRow("no_rider_available", "SYSTEM", 25)),
                List.of(),
                List.of()));

    Map<String, Object> data = service.cancellations(ops, "TODAY", null, null, null);

    List<Map<String, Object>> byReason = (List<Map<String, Object>>) data.get("by_reason");
    assertThat(byReason)
        .extracting(m -> m.get("actor"))
        .containsExactlyInAnyOrder("pharmacy", "customer", "system");
    BigDecimal sum =
        byReason.stream()
            .map(m -> (BigDecimal) m.get("pct"))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo("100.0");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac005_slaAdherenceUsesOnlyDeliveredOrders() {
    // 70 delivered, 7 breached → 90% adherence; non-delivered ignored in denom
    when(store.liveOps(any(), any(), isNull()))
        .thenReturn(sampleOps(100, 90, 80, 75, 70, 10, 5, 95, 7));

    Map<String, Object> data = service.operations(ops, "TODAY", null, null, null);
    Map<String, Object> kpis = (Map<String, Object>) data.get("kpis");
    Map<String, Object> sla = (Map<String, Object>) kpis.get("sla_adherence_pct");
    assertThat((BigDecimal) sla.get("value")).isEqualByComparingTo("90.0");
  }

  @Test
  void ac006_invalidZoneReturns404_andValidZoneScoped() {
    UUID zoneId = UUID.randomUUID();
    when(store.zoneExists(zoneId)).thenReturn(true);
    when(store.liveOps(any(), any(), eq(zoneId)))
        .thenReturn(sampleOps(10, 9, 8, 7, 6, 1, 0, 10, 0));
    when(store.liveOrdersNow(eq(zoneId))).thenReturn(3L);

    Map<String, Object> data = service.operations(ops, "TODAY", null, null, zoneId.toString());
    assertThat(data.get("period")).isEqualTo("TODAY");
    verify(store, org.mockito.Mockito.atLeastOnce()).liveOps(any(), any(), eq(zoneId));

    UUID missing = UUID.randomUUID();
    when(store.zoneExists(missing)).thenReturn(false);
    assertThatThrownBy(() -> service.operations(ops, "TODAY", null, null, missing.toString()))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ZONE_NOT_FOUND");

    assertThatThrownBy(() -> service.operations(ops, "TODAY", null, null, "not-a-uuid"))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ZONE_NOT_FOUND");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac007_fillRateExcludesPreAcceptCancelsFromDenom() {
    // packed=80, fillDenom=95 (placed 100 − preAccept 5) → 84.2%
    when(store.liveOps(any(), any(), isNull()))
        .thenReturn(sampleOps(100, 90, 80, 75, 70, 10, 5, 95, 3));

    Map<String, Object> data = service.operations(ops, "TODAY", null, null, null);
    Map<String, Object> kpis = (Map<String, Object>) data.get("kpis");
    Map<String, Object> fill = (Map<String, Object>) kpis.get("fill_rate_pct");
    assertThat((BigDecimal) fill.get("value")).isEqualByComparingTo("84.2");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac008_todayLiveOrdersSupportsThirtySecondRefresh() {
    when(store.liveOps(any(), any(), isNull())).thenReturn(sampleOps(1, 1, 1, 1, 1, 0, 0, 1, 0));
    when(store.liveOrdersNow(isNull())).thenReturn(12L);

    Map<String, Object> data = service.operations(ops, "TODAY", null, null, null);
    Map<String, Object> live =
        (Map<String, Object>) ((Map<String, Object>) data.get("kpis")).get("live_orders_now");
    assertThat(OperationsAnalyticsService.LIVE_ORDERS_REFRESH_SECONDS).isEqualTo(30);
    assertThat(live.get("refresh_seconds")).isEqualTo(30);
    verify(store).liveOrdersNow(isNull());

    // Non-TODAY clears live count (Command Center uses TODAY only)
    org.mockito.Mockito.clearInvocations(store);
    when(store.aggregatedOps(any(), any(), isNull()))
        .thenReturn(sampleOps(1, 1, 1, 1, 1, 0, 0, 1, 0));
    Map<String, Object> week = service.operations(ops, "7D", null, null, null);
    Map<String, Object> weekLive =
        (Map<String, Object>) ((Map<String, Object>) week.get("kpis")).get("live_orders_now");
    assertThat(weekLive.get("value")).isNull();
    verify(store, never()).liveOrdersNow(any());
  }

  @Test
  void forbiddenForSupport() {
    assertThatThrownBy(() -> service.operations(support, "TODAY", null, null, null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void invalidPeriod() {
    assertThatThrownBy(() -> service.operations(ops, "YESTERDAY", null, null, null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD");
  }

  @Test
  @SuppressWarnings("unchecked")
  void cancellationsIncludePharmacyAndZoneRows() {
    UUID ph = UUID.randomUUID();
    UUID zone = UUID.randomUUID();
    when(store.liveOps(any(), any(), isNull()))
        .thenReturn(sampleOps(100, 90, 80, 70, 60, 10, 2, 98, 1));
    when(store.liveCancellations(any(), any(), isNull()))
        .thenReturn(
            new CancelSummary(
                10,
                2,
                8,
                List.of(new CancelReasonRow("out_of_stock", "PHARMACY", 10)),
                List.of(new CancelPharmacyRow(ph, "Medplus", 5, 40)),
                List.of(new CancelZoneRow(zone, "HSR", 4, 50))));

    Map<String, Object> data = service.cancellations(ops, "TODAY", null, null, null);
    assertThat((List<?>) data.get("top_pharmacies_by_cancellation")).hasSize(1);
    assertThat((List<?>) data.get("by_zone")).hasSize(1);
  }

  private static OpsTotals sampleOps(
      long placed,
      long accepted,
      long packed,
      long ofd,
      long delivered,
      long cancelled,
      long preAccept,
      long fillDenom,
      long slaBreached) {
    return new OpsTotals(
        placed,
        accepted,
        packed,
        ofd,
        delivered,
        cancelled,
        preAccept,
        fillDenom,
        slaBreached,
        packed * 492L,
        delivered * 2000L,
        45);
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }
}

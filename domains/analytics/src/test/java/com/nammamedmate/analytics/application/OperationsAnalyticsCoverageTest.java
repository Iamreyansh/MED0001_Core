package com.nammamedmate.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.port.out.PlatformOpsStore;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OperationsAnalyticsCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T16:00:00Z");

  @Mock private PlatformOpsStore store;

  @Test
  @SuppressWarnings("unchecked")
  void coversNullPrincipalAggregatedCancelEmptyReasonsAndSuperRole() {
    OperationsAnalyticsService service =
        new OperationsAnalyticsService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> service.operations(null, "TODAY", null, null, null))
        .isInstanceOf(AppException.class);

    MedmatePrincipal superAdmin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    OpsTotals empty = new OpsTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 45);
    when(store.aggregatedOps(any(), any(), isNull())).thenReturn(empty);
    when(store.aggregatedCancellations(any(), any(), isNull()))
        .thenReturn(new CancelSummary(0, 0, 0, List.of(), List.of(), List.of()));
    Map<String, Object> cancel = service.cancellations(superAdmin, "7D", null, null, null);
    assertThat((List<?>) cancel.get("by_reason")).isEmpty();

    PercentilePair z = new PercentilePair(BigDecimal.ZERO.setScale(1), BigDecimal.ZERO.setScale(1));
    when(store.liveDeliveryPlatform(any(), any())).thenReturn(new DeliverySegment(z, z, z, z));
    when(store.liveDeliveryByZone(any(), any()))
        .thenReturn(
            List.of(
                new ZoneDeliveryRow(null, "Unknown", new DeliverySegment(z, z, z, z), z.p50())));
    Map<String, Object> breakdown = service.deliveryBreakdown(superAdmin, "7D", null, null);
    List<Map<String, Object>> byZone = (List<Map<String, Object>>) breakdown.get("by_zone");
    assertThat(byZone.getFirst().get("zone_id")).isNull();

    when(store.aggregatedCancellations(any(), any(), isNull()))
        .thenReturn(
            new CancelSummary(
                3,
                1,
                2,
                List.of(new CancelReasonRow("timeout", "SYSTEM", 3)),
                List.of(),
                List.of(new CancelZoneRow(null, "Unknown", 1, 10))));
    Map<String, Object> one = service.cancellations(superAdmin, "7D", null, null, null);
    List<Map<String, Object>> reasons = (List<Map<String, Object>>) one.get("by_reason");
    assertThat((BigDecimal) reasons.getFirst().get("pct")).isEqualByComparingTo("100.0");
    List<Map<String, Object>> zones = (List<Map<String, Object>>) one.get("by_zone");
    assertThat(zones.getFirst().get("zone_id")).isNull();

    // blank zone id is treated as platform-wide
    when(store.liveOps(any(), any(), isNull())).thenReturn(empty);
    when(store.liveOrdersNow(isNull())).thenReturn(0L);
    assertThat(service.operations(superAdmin, "TODAY", null, null, "  ").get("period"))
        .isEqualTo("TODAY");

    // total==0 with a reason row forces last-pct zero branch
    when(store.aggregatedCancellations(any(), any(), isNull()))
        .thenReturn(
            new CancelSummary(
                0,
                0,
                0,
                List.of(new CancelReasonRow("timeout", "SYSTEM", 0)),
                List.of(),
                List.of()));
    Map<String, Object> zero = service.cancellations(superAdmin, "7D", null, null, null);
    List<Map<String, Object>> zeroReasons = (List<Map<String, Object>>) zero.get("by_reason");
    assertThat((BigDecimal) zeroReasons.getFirst().get("pct")).isEqualByComparingTo("0.0");
  }

  @Test
  void opsRefreshSkipsAfterBusinessEnd() {
    PlatformOpsStore opsStore = store;
    // 2026-07-24 18:00 UTC = 23:30 IST — after 23:00
    OpsSnapshotRefreshService svc =
        new OpsSnapshotRefreshService(
            opsStore, Clock.fixed(Instant.parse("2026-07-24T18:00:00Z"), ZoneOffset.UTC));
    svc.refreshIfBusinessHours();
    verify(opsStore, never()).refreshOpsSnapshots(any(), any());
  }

  @Test
  void opsRefreshAtBusinessEndBoundaryRuns() {
    // 2026-07-24 17:30 UTC = 23:00 IST — inclusive end
    OpsSnapshotRefreshService svc =
        new OpsSnapshotRefreshService(
            store, Clock.fixed(Instant.parse("2026-07-24T17:30:00Z"), ZoneOffset.UTC));
    svc.refreshIfBusinessHours();
    verify(store).refreshOpsSnapshots(LocalDate.of(2026, 7, 24), LocalDate.of(2026, 7, 24));
  }
}

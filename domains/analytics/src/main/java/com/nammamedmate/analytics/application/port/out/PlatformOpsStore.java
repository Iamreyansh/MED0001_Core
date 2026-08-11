package com.nammamedmate.analytics.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read/write ports for operations & SLA analytics (EPIC-016 STORY-002). */
public interface PlatformOpsStore {

  record OpsTotals(
      long ordersPlaced,
      long ordersAccepted,
      long ordersPacked,
      long ordersOutForDelivery,
      long ordersDelivered,
      long ordersCancelled,
      long preAcceptCancelled,
      long fillDenom,
      long slaBreached,
      long totalPrepSeconds,
      long totalDeliverySeconds,
      int slaThresholdMinutes) {}

  record PercentilePair(BigDecimal p50, BigDecimal p90) {}

  record DeliverySegment(
      PercentilePair pharmacyPrep,
      PercentilePair riderPickup,
      PercentilePair delivery,
      PercentilePair total) {}

  record ZoneDeliveryRow(
      UUID zoneId, String zoneName, DeliverySegment segment, BigDecimal slaAdherencePct) {}

  record CancelReasonRow(String reason, String actor, long count) {}

  record CancelPharmacyRow(UUID pharmacyId, String name, long cancellations, long pharmacyOrders) {}

  record CancelZoneRow(UUID zoneId, String zoneName, long cancellations, long zoneOrders) {}

  record CancelSummary(
      long totalCancellations,
      long preAccept,
      long postAccept,
      List<CancelReasonRow> byReason,
      List<CancelPharmacyRow> topPharmacies,
      List<CancelZoneRow> byZone) {
    public CancelSummary {
      byReason = List.copyOf(byReason);
      topPharmacies = List.copyOf(topPharmacies);
      byZone = List.copyOf(byZone);
    }
  }

  boolean zoneExists(UUID zoneId);

  long liveOrdersNow(UUID zoneIdOrNull);

  OpsTotals liveOps(Instant fromInclusive, Instant toExclusive, UUID zoneIdOrNull);

  OpsTotals aggregatedOps(LocalDate fromInclusive, LocalDate toInclusive, UUID zoneIdOrNull);

  DeliverySegment liveDeliveryPlatform(Instant fromInclusive, Instant toExclusive);

  List<ZoneDeliveryRow> liveDeliveryByZone(Instant fromInclusive, Instant toExclusive);

  CancelSummary liveCancellations(Instant fromInclusive, Instant toExclusive, UUID zoneIdOrNull);

  CancelSummary aggregatedCancellations(
      Instant fromInclusive, Instant toExclusive, UUID zoneIdOrNull);

  /** Refresh ops snapshots + cancellation facts for IST calendar dates [from, to]. */
  void refreshOpsSnapshots(LocalDate fromInclusive, LocalDate toInclusive);
}

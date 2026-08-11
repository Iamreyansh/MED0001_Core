package com.nammamedmate.analytics.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read/write ports for geography analytics (EPIC-016 STORY-005). */
public interface PlatformGeographyStore {

  record ZoneMetrics(
      UUID zoneId,
      String zoneName,
      long gmvPaise,
      long orders,
      long slaBreached,
      long totalDeliverySeconds,
      BigDecimal avgRidersOnline,
      int pharmaciesCount,
      BigDecimal pharmacyCoveragePct,
      int unservedAttempts) {}

  record LiveRiderCount(UUID zoneId, long ridersOnline) {}

  record HourlyDemandCell(
      UUID zoneId, String zoneName, int hourOfDay, int dayOfWeek, BigDecimal avgOrders) {}

  boolean zoneExists(UUID zoneId);

  List<ZoneMetrics> liveZoneMetrics(Instant fromInclusive, Instant toExclusive);

  List<ZoneMetrics> aggregatedZoneMetrics(LocalDate fromInclusive, LocalDate toInclusive);

  /** Live ONLINE/ON_TRIP riders per zone (current_zone_id, else primary_zone_id). */
  List<LiveRiderCount> liveRidersOnlineByZone();

  /**
   * Precomputed heatmap cells only — never scans raw orders (AC-008). Empty when not yet refreshed.
   */
  List<HourlyDemandCell> heatmapCells(UUID zoneIdOrNull);

  /** Refresh analytics_zone_daily for IST calendar dates [from, to]. */
  void refreshZoneDaily(LocalDate fromInclusive, LocalDate toInclusive);

  /**
   * Recompute analytics_zone_hourly_demand over the rolling {@code windowDays} ending yesterday.
   */
  void refreshHourlyDemand(LocalDate asOfExclusiveEnd, int windowDays);
}

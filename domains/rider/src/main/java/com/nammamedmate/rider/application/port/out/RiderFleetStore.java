package com.nammamedmate.rider.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiderFleetStore {

  record FleetRiderRow(
      UUID riderId,
      String name,
      String phone,
      UUID zoneId,
      String zoneName,
      String vehicleType,
      String accountStatus,
      UUID currentZoneId,
      Instant lastLocationAt,
      BigDecimal avgRating,
      BigDecimal onTimePct,
      int dailyStreakDays,
      long earningsWalletBalancePaise) {}

  record FleetFilter(UUID zoneId, String status, int page, int limit) {}

  record FleetPage(List<FleetRiderRow> rows, long total) {
    public FleetPage {
      rows = List.copyOf(rows);
    }
  }

  FleetPage listFleet(FleetFilter filter);

  List<FleetRiderRow> listByZone(UUID zoneId);

  Optional<FleetRiderRow> findFleetRow(UUID riderId);

  int countTripsToday(UUID riderId, Instant dayStartUtc, Instant dayEndUtc);

  long sumShiftEarningsTodayPaise(UUID riderId, Instant dayStartUtc, Instant dayEndUtc);
}

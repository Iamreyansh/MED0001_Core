package com.nammamedmate.rider.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface GeofenceBreachStore {

  record BreachRecord(
      UUID id,
      UUID riderId,
      UUID zoneId,
      UUID orderId,
      BigDecimal breachLat,
      BigDecimal breachLng,
      boolean alertSent,
      Instant detectedAt) {}

  void insert(BreachRecord row);

  /** True if a breach for rider+zone was logged after {@code since}. */
  boolean existsSince(UUID riderId, UUID zoneId, Instant since);
}

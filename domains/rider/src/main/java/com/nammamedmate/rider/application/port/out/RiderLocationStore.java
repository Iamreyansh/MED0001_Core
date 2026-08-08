package com.nammamedmate.rider.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiderLocationStore {

  record LocationPoint(
      UUID id,
      UUID riderId,
      UUID orderId,
      BigDecimal lat,
      BigDecimal lng,
      BigDecimal accuracyM,
      BigDecimal speedKmh,
      BigDecimal heading,
      boolean lowAccuracy,
      Instant recordedAt,
      Instant createdAt) {}

  void insertBatch(List<LocationPoint> points);

  List<LocationPoint> findByRiderAndOrder(UUID riderId, UUID orderId);

  Optional<Instant> findOldestRecordedAt(UUID riderId, UUID orderId);

  int purgeOlderThan(Instant cutoff);

  Optional<LocationPoint> findLatestByRider(UUID riderId);
}

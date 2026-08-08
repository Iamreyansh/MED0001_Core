package com.nammamedmate.rider.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Redis live GPS hash `rider_location:{rider_id}` TTL 5m (STORY-004 BR-008). */
public interface RiderLocationCachePort {

  record LiveLocation(
      double lat,
      double lng,
      Double heading,
      Double speedKmh,
      Double accuracyM,
      UUID orderId,
      Instant updatedAt) {}

  void put(UUID riderId, LiveLocation location, Duration ttl);

  Optional<LiveLocation> get(UUID riderId);

  void evict(UUID riderId);
}

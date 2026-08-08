package com.nammamedmate.rider.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Assignment counters for acceptance / cancel rates (STORY-008). */
public interface RiderAssignmentStatsPort {

  record Stats(
      long assigned,
      long accepted,
      long cancelled,
      long delivered,
      Double avgPickupMinutes,
      Double avgDeliveryMinutes) {}

  Stats statsForRider(UUID riderId);

  default Optional<Stats> find(UUID riderId) {
    return Optional.of(statsForRider(riderId));
  }
}

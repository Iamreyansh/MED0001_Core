package com.nammamedmate.rider.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RiderShiftStore {

  record ShiftRecord(
      UUID id,
      UUID riderId,
      UUID zoneId,
      Instant shiftStart,
      Instant shiftEnd,
      Integer durationMinutes,
      int tripsInShift,
      long earningsInShiftPaise,
      UUID forceClosedBy,
      Instant createdAt) {}

  void insert(ShiftRecord shift);

  void close(UUID shiftId, Instant shiftEnd, int durationMinutes, UUID forceClosedBy);

  Optional<ShiftRecord> findOpenByRider(UUID riderId);

  /** Sum of closed+open duration minutes for shifts starting on the given UTC day window. */
  int sumDurationMinutesForRiderBetween(UUID riderId, Instant fromInclusive, Instant toExclusive);

  Optional<ShiftRecord> findLatestClosedByRider(UUID riderId);
}

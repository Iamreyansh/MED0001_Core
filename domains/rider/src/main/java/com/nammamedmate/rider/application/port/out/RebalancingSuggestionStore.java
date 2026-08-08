package com.nammamedmate.rider.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RebalancingSuggestionStore {

  record SuggestionRow(
      UUID id,
      UUID fromZoneId,
      String fromZoneName,
      UUID toZoneId,
      String toZoneName,
      int ridersToMove,
      String reason,
      BigDecimal confidencePct,
      String suggestedRidersJson,
      String status,
      UUID appliedBy,
      Instant appliedAt,
      Instant expiresAt,
      Instant generatedAt) {}

  record SuggestedRider(UUID riderId, String name, BigDecimal distanceToTargetKm) {}

  void insert(
      UUID id,
      UUID fromZoneId,
      UUID toZoneId,
      int ridersToMove,
      String reason,
      BigDecimal confidencePct,
      String suggestedRidersJson,
      Instant expiresAt,
      Instant generatedAt);

  List<SuggestionRow> listPending(Instant now);

  Optional<SuggestionRow> findById(UUID id);

  boolean markApplied(UUID id, UUID appliedBy, Instant appliedAt);

  void expireStale(Instant now);
}

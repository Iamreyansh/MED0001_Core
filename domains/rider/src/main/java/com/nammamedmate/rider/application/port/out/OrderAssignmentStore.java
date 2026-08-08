package com.nammamedmate.rider.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderAssignmentStore {

  record AssignmentRecord(
      UUID id,
      UUID orderId,
      UUID riderId,
      String assignmentType,
      UUID assignedBy,
      String status,
      Instant acceptDeadline,
      Instant acceptedAt,
      Instant pickupConfirmedAt,
      Instant deliveredAt,
      String pickupOtpHash,
      String deliveryOtpHash,
      String reassignReason,
      BigDecimal compositeScore,
      Instant createdAt,
      Instant updatedAt) {}

  void insert(AssignmentRecord row);

  void update(AssignmentRecord row);

  Optional<AssignmentRecord> findById(UUID id);

  Optional<AssignmentRecord> findActiveByOrder(UUID orderId);

  /** Any status (incl. DELIVERED) — for location-history retention checks. */
  default Optional<AssignmentRecord> findLatestByOrderAndRider(UUID orderId, UUID riderId) {
    return Optional.empty();
  }

  Optional<AssignmentRecord> findCurrentForRider(UUID riderId);

  int countActiveForRider(UUID riderId);

  List<AssignmentRecord> findPendingPastDeadline(Instant now, int limit);

  boolean hasActiveForOrder(UUID orderId);
}

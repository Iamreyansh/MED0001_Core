package com.nammamedmate.rider.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RiderStatusAuditStore {

  record AuditRecord(
      UUID id,
      UUID riderId,
      UUID changedBy,
      String changedByRole,
      String fromStatus,
      String toStatus,
      String reason,
      Instant createdAt) {}

  void insert(AuditRecord record);

  Optional<AuditRecord> findLatestForceChange(UUID riderId);
}

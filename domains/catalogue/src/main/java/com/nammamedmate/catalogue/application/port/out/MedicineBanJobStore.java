package com.nammamedmate.catalogue.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MedicineBanJobStore {

  record BanJobRow(
      UUID id,
      UUID medicineId,
      String status,
      int mappingsHidden,
      String reason,
      UUID initiatedBy,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt) {}

  void insertQueued(UUID id, UUID medicineId, String reason, UUID initiatedBy, Instant createdAt);

  void markRunning(UUID id, Instant startedAt);

  void markCompleted(UUID id, int mappingsHidden, Instant completedAt);

  Optional<BanJobRow> findById(UUID id);
}

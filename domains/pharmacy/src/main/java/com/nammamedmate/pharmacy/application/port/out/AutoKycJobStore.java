package com.nammamedmate.pharmacy.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AutoKycJobStore {

  record AutoKycJobRecord(
      UUID id,
      UUID pharmacyId,
      UUID triggeredBy,
      String triggerSource,
      String overallStatus,
      boolean autoActivated,
      Instant triggeredAt,
      Instant completedAt) {}

  void insert(AutoKycJobRecord job);

  Optional<AutoKycJobRecord> findById(UUID jobId);

  Optional<AutoKycJobRecord> findLatestByPharmacy(UUID pharmacyId);

  Optional<AutoKycJobRecord> findInProgressByPharmacy(UUID pharmacyId);

  void updateOverallStatus(UUID jobId, String overallStatus, Instant completedAt);

  void markAutoActivated(UUID jobId, Instant completedAt);
}

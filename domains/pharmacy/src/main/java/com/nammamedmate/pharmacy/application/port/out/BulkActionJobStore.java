package com.nammamedmate.pharmacy.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface BulkActionJobStore {

  record JobRow(
      UUID id,
      String action,
      Map<String, Object> payload,
      List<UUID> pharmacyIds,
      String status,
      int totalPharmacies,
      int processed,
      int succeeded,
      int failed,
      int skipped,
      List<Map<String, Object>> skippedPharmacies,
      Map<String, Object> resultPayload,
      UUID initiatedBy,
      Instant startedAt,
      Instant completedAt,
      Instant createdAt) {
    public JobRow {
      if (pharmacyIds != null) {
        pharmacyIds = List.copyOf(pharmacyIds);
      }
      if (skippedPharmacies != null) {
        skippedPharmacies = List.copyOf(skippedPharmacies);
      }
      if (payload != null) {
        payload = Map.copyOf(payload);
      }
      if (resultPayload != null) {
        resultPayload = Map.copyOf(resultPayload);
      }
    }
  }

  void insert(JobRow row);

  Optional<JobRow> findById(UUID jobId);

  List<JobRow> findQueued(int limit);

  void markRunning(UUID jobId, Instant startedAt);

  void updateProgress(
      UUID jobId,
      int processed,
      int succeeded,
      int failed,
      int skipped,
      List<Map<String, Object>> skippedPharmacies);

  void markCompleted(
      UUID jobId,
      int processed,
      int succeeded,
      int failed,
      int skipped,
      List<Map<String, Object>> skippedPharmacies,
      Map<String, Object> resultPayload,
      Instant completedAt);
}

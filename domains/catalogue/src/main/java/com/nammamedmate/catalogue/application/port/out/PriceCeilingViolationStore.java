package com.nammamedmate.catalogue.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PriceCeilingViolationStore {

  record ViolationRow(
      UUID id,
      UUID medicineId,
      String medicineName,
      long ceilingPaise,
      UUID pharmacyId,
      String pharmacyName,
      long pharmacyPricePaise,
      long overagePaise,
      String zoneName,
      Instant detectedAt,
      Instant lastNotifiedAt,
      String status) {}

  record ViolationListResult(List<ViolationRow> rows, long total) {
    public ViolationListResult {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  record OpenViolation(
      UUID id,
      UUID medicineId,
      String medicineName,
      UUID pharmacyId,
      long ceilingPaise,
      long pharmacyPricePaise) {}

  void upsertOpen(
      UUID id,
      UUID medicineId,
      UUID pharmacyId,
      long ceilingPaise,
      long pharmacyPricePaise,
      Instant detectedAt);

  int resolveOpenForMedicine(UUID medicineId, Instant resolvedAt);

  void resolveStale(Instant resolvedAt);

  ViolationListResult list(UUID medicineId, UUID zoneId, int page, int limit);

  List<OpenViolation> listOpen(UUID medicineId);

  void markNotified(List<UUID> violationIds, Instant notifiedAt);
}

package com.nammamedmate.catalogue.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PriceCeilingStore {

  record CeilingRow(
      UUID medicineId,
      String medicineName,
      String categoryName,
      String schedule,
      long mrpPaise,
      long ceilingPaise,
      long pharmaciesAboveCeiling,
      LocalDate effectiveFrom,
      UUID setById,
      String setByName,
      String setByRole,
      Instant setAt,
      String reason) {}

  record CeilingListResult(List<CeilingRow> rows, long total) {
    public CeilingListResult {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  record AboveCeilingMapping(
      UUID pharmacyId, UUID medicineId, long pharmacyPricePaise, long ceilingPaise) {}

  void setCeiling(
      UUID medicineId,
      long ceilingPaise,
      LocalDate effectiveFrom,
      String reason,
      UUID setById,
      String setByName,
      String setByRole,
      Instant setAt);

  void clearCeiling(UUID medicineId, Instant updatedAt);

  CeilingListResult listCeilings(UUID categoryId, Boolean hasViolations, int page, int limit);

  Optional<String> findAdminName(UUID adminId);

  List<AboveCeilingMapping> findAboveCeilingMappings(UUID medicineId);

  List<AboveCeilingMapping> findAllAboveCeilingMappings();

  long countAboveCeiling(UUID medicineId);
}

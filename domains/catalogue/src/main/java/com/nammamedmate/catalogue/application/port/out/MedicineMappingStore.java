package com.nammamedmate.catalogue.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MedicineMappingStore {

  record MappingRow(
      UUID id,
      UUID pharmacyId,
      UUID masterMedicineId,
      long pharmacyPricePaise,
      int stockQuantity,
      boolean visible,
      Instant createdAt,
      Instant updatedAt) {}

  record MappingListRow(
      UUID id,
      UUID masterMedicineId,
      String name,
      String saltComposition,
      String manufacturer,
      String categoryName,
      String form,
      BigDecimal packSize,
      String schedule,
      boolean rxOnly,
      long masterMrpPaise,
      Long mrpCeilingPaise,
      long pharmacyPricePaise,
      int stockQuantity,
      boolean visible,
      Instant createdAt,
      Instant updatedAt) {}

  record AdminMappingRow(
      UUID mappingId,
      UUID pharmacyId,
      String pharmacyName,
      String zoneName,
      long pharmacyPricePaise,
      int stockQuantity,
      boolean visible,
      boolean aboveCeiling,
      Instant createdAt) {}

  record AdminListResult(List<AdminMappingRow> rows, long total, long totalStocking) {
    public AdminListResult {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  record ListResult(List<MappingListRow> rows, long total) {
    public ListResult {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  record PharmacyListFilter(
      UUID pharmacyId,
      Boolean visible,
      Boolean inStock,
      UUID categoryId,
      String search,
      String sort,
      String order,
      int page,
      int limit) {}

  record AdminListFilter(
      UUID masterMedicineId,
      UUID zoneId,
      Boolean visible,
      boolean aboveCeilingOnly,
      int page,
      int limit) {}

  record MedicineRef(
      UUID id, String name, long mrpPaise, Long mrpCeilingPaise, String schedule, boolean banned) {}

  record CatalogueStats(int mappedSkus, int inStockSkus, int outOfStockSkus) {}

  void insert(MappingRow row);

  Optional<MappingRow> findById(UUID mappingId);

  Optional<MappingRow> findByPharmacyAndMedicine(UUID pharmacyId, UUID medicineId);

  boolean exists(UUID pharmacyId, UUID medicineId);

  void update(
      UUID mappingId,
      Long pharmacyPricePaise,
      Integer stockQuantity,
      Boolean visible,
      Instant updatedAt);

  void delete(UUID mappingId);

  ListResult listForPharmacy(PharmacyListFilter filter);

  AdminListResult listForAdmin(AdminListFilter filter);

  Optional<MedicineRef> findMedicine(UUID medicineId);

  Optional<String> pharmacyStatus(UUID pharmacyId);

  int hideAllForMedicine(UUID medicineId);

  int hideAllForPharmacy(UUID pharmacyId);

  void restoreAllForPharmacy(UUID pharmacyId);

  CatalogueStats statsForPharmacy(UUID pharmacyId);

  void incrementMappedCount(UUID medicineId, int delta);

  void insertBulkJob(
      UUID jobId, List<UUID> pharmacyIds, Object payload, UUID initiatedBy, Instant createdAt);

  Optional<BulkJobRow> findBulkJob(UUID jobId);

  void markBulkJobRunning(UUID jobId, Instant startedAt);

  void markBulkJobCompleted(
      UUID jobId,
      int processed,
      int succeeded,
      int failed,
      int skipped,
      List<Object> skippedPharmacies,
      Instant completedAt);

  List<BulkJobRow> findQueuedBulkMapJobs(int limit);

  record BulkJobRow(
      UUID id,
      String action,
      String status,
      List<UUID> pharmacyIds,
      Object payload,
      UUID initiatedBy,
      Instant createdAt) {
    public BulkJobRow {
      pharmacyIds = pharmacyIds == null ? List.of() : List.copyOf(pharmacyIds);
    }
  }
}

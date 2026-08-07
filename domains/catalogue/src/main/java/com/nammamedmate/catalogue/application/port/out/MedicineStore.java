package com.nammamedmate.catalogue.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MedicineStore {

  record MedicineRow(
      UUID id,
      String name,
      String saltComposition,
      String manufacturer,
      UUID categoryId,
      String categoryName,
      String form,
      BigDecimal packSize,
      String packUnit,
      String schedule,
      String hsnCode,
      int gstPct,
      long mrpPaise,
      Long mrpCeilingPaise,
      boolean rxOnly,
      boolean banned,
      String banReason,
      int monthlyDemand,
      int mappedPharmacyCount,
      List<UUID> substitutes,
      String description,
      UUID createdBy,
      Instant createdAt,
      Instant updatedAt) {
    public MedicineRow {
      substitutes = substitutes == null ? List.of() : List.copyOf(substitutes);
    }
  }

  record SubstituteRef(UUID medicineId, String name, String manufacturer) {}

  record ListFilter(
      UUID categoryId,
      String schedule,
      Integer gstPct,
      Boolean rxOnly,
      boolean bannedOnly,
      String search,
      String sort,
      String order,
      int page,
      int limit) {}

  record ListResult(List<MedicineRow> rows, long total) {
    public ListResult {
      rows = rows == null ? List.of() : List.copyOf(rows);
    }
  }

  record SummaryStats(
      long totalSkus,
      long categoryCount,
      long rxOnlyCount,
      long otcCount,
      long bannedCount,
      long scheduleHCount,
      long scheduleH1Count,
      long scheduleXCount,
      Long avgMrpPaise,
      long totalPharmacyMappings,
      Instant dataAsOf) {}

  void insert(MedicineRow row);

  void update(
      UUID id,
      String name,
      String description,
      UUID categoryId,
      String schedule,
      Integer gstPct,
      Long mrpPaise,
      Boolean rxOnly,
      List<UUID> substitutes,
      Instant updatedAt);

  void setBanned(UUID id, boolean banned, String banReason, Instant updatedAt);

  Optional<MedicineRow> findById(UUID id);

  ListResult list(ListFilter filter);

  SummaryStats summary(Instant asOf);

  boolean hsnExists(String hsnCode);

  boolean categoryActive(UUID categoryId);

  int countExistingIds(List<UUID> ids);

  List<SubstituteRef> findSubstituteRefs(List<UUID> ids);

  List<UUID> listAllIds();

  void updateMonthlyDemand(UUID id, int monthlyDemand, Instant updatedAt);

  int countActiveByCategoryId(UUID categoryId);
}

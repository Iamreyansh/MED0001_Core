package com.nammamedmate.inventory.application.port.out;

import com.nammamedmate.inventory.domain.PharmacyProduct;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyProductStore {

  record ListFilter(
      UUID pharmacyId,
      String tab,
      String q,
      String sort,
      String order,
      int page,
      int limit,
      UUID categoryId) {}

  record ListResult(List<PharmacyProduct> rows, long total, Map<String, Long> tabCounts) {
    public ListResult {
      rows = rows == null ? List.of() : List.copyOf(rows);
      tabCounts = tabCounts == null ? Map.of() : Map.copyOf(tabCounts);
    }
  }

  record SummaryRow(
      long totalSkus,
      long totalUnits,
      long stockValueAtCostPaise,
      long retailValueMrpPaise,
      long lowStockCount,
      long expiringCount,
      long deadStockCount,
      long outOfStockCount,
      long unallocatedCount) {}

  record SettingsPatch(
      Boolean isLooseSellingEnabled,
      Boolean isOnlineVisible,
      Integer reorderLevel,
      String rackLocationCode) {}

  record DetailsPatch(
      String name,
      String saltComposition,
      String manufacturer,
      Integer packSize,
      String packUnit,
      UUID categoryId,
      String form,
      String schedule,
      String hsnCode,
      BigDecimal gstPct,
      List<String> rackLocations,
      String productPhotoUrl) {
    public DetailsPatch {
      rackLocations = rackLocations == null ? null : List.copyOf(rackLocations);
    }
  }

  ListResult list(ListFilter filter, Instant now);

  SummaryRow summary(UUID pharmacyId, Instant now);

  Optional<PharmacyProduct> findById(UUID pharmacyId, UUID productId);

  Optional<PharmacyProduct> findByNameAndManufacturer(
      UUID pharmacyId, String name, String manufacturer);

  List<PharmacyProduct> searchByName(UUID pharmacyId, String query, int limit);

  PharmacyProduct insert(PharmacyProduct product);

  void updateMrp(UUID pharmacyId, UUID productId, long mrpPaise, Instant now);

  Optional<PharmacyProduct> updateSettings(
      UUID pharmacyId, UUID productId, SettingsPatch patch, Instant now);

  Optional<PharmacyProduct> updateDetails(
      UUID pharmacyId, UUID productId, DetailsPatch patch, Instant now);

  /** All rows matching filters (no pagination) for sync export. */
  List<PharmacyProduct> listAllForExport(ListFilter filter, Instant now);
}

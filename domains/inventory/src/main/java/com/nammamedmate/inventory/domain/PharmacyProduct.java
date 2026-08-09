package com.nammamedmate.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PharmacyProduct(
    UUID id,
    UUID pharmacyId,
    UUID masterMedicineId,
    String name,
    String saltComposition,
    String manufacturer,
    int packSize,
    String packUnit,
    UUID categoryId,
    String categoryName,
    String form,
    String schedule,
    String hsnCode,
    BigDecimal gstPct,
    long mrpPaise,
    boolean isRxOnly,
    boolean isLooseSellingEnabled,
    boolean isOnlineVisible,
    int reorderLevel,
    List<String> rackLocations,
    int totalStockUnits,
    int totalBatches,
    LocalDate earliestExpiry,
    long costValuePaise,
    Instant lastMovementAt,
    String productPhotoUrl,
    Instant createdAt,
    Instant updatedAt) {

  public PharmacyProduct {
    rackLocations = rackLocations == null ? List.of() : List.copyOf(rackLocations);
  }

  public int totalStockPacks() {
    return packSize <= 0 ? 0 : totalStockUnits / packSize;
  }

  public long mrpValuePaise() {
    return Math.multiplyExact(mrpPaise, totalStockUnits);
  }
}

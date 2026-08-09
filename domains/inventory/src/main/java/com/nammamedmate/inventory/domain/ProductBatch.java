package com.nammamedmate.inventory.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ProductBatch(
    UUID id,
    UUID productId,
    UUID pharmacyId,
    String batchNumber,
    LocalDate expiryDate,
    LocalDate manufacturedDate,
    int quantityReceived,
    int quantityCurrent,
    long purchasePricePaise,
    long mrpPaise,
    boolean isActive,
    String writeOffReason,
    String writeOffNotes,
    UUID grnItemId,
    Instant createdAt,
    Instant updatedAt) {

  public long valueAtRiskPaise() {
    return Math.multiplyExact((long) quantityCurrent, purchasePricePaise);
  }

  public boolean isExpired(LocalDate today) {
    return expiryDate.isBefore(today);
  }
}

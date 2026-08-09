package com.nammamedmate.inventory.domain;

import java.time.Instant;
import java.util.UUID;

public record PurchaseOrder(
    UUID id,
    UUID pharmacyId,
    UUID distributorId,
    String poNumber,
    PurchaseOrderStatus status,
    UUID createdBy,
    Instant sentAt,
    PoSentChannel sentChannel,
    UUID grnId,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public boolean editable() {
    return status == PurchaseOrderStatus.DRAFT && deletedAt == null;
  }
}

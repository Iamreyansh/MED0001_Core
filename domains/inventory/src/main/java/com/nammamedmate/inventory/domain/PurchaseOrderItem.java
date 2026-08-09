package com.nammamedmate.inventory.domain;

import java.time.Instant;
import java.util.UUID;

public record PurchaseOrderItem(
    UUID id,
    UUID poId,
    UUID pharmacyId,
    UUID productId,
    int quantity,
    Long estimatedPricePaise,
    Instant createdAt) {}

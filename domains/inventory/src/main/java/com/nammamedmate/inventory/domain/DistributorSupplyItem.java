package com.nammamedmate.inventory.domain;

import java.time.Instant;
import java.util.UUID;

public record DistributorSupplyItem(
    UUID id,
    UUID distributorId,
    UUID productId,
    UUID pharmacyId,
    long purchasePricePaise,
    String schemeDescription,
    boolean preferredSource,
    Instant lastPurchasedAt,
    Instant updatedAt) {}

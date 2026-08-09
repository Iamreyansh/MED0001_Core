package com.nammamedmate.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ReorderSuggestionSnapshot(
    UUID id,
    UUID pharmacyId,
    UUID productId,
    int currentStock,
    int reorderLevel,
    BigDecimal daysOfCover,
    UUID bestDistributorId,
    Long landedPricePaise,
    LocalDate snapshotDate,
    Instant createdAt) {}

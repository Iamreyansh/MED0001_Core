package com.nammamedmate.inventory.domain;

import java.time.Instant;
import java.util.UUID;

public record RackLocation(
    UUID id,
    UUID pharmacyId,
    String rackCode,
    String zoneName,
    String description,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {}

package com.nammamedmate.inventory.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PurchaseGrn(
    UUID id,
    UUID pharmacyId,
    UUID distributorId,
    String invoiceNumber,
    LocalDate invoiceDate,
    GrnStatus status,
    Instant stockedAt,
    UUID stockedBy,
    UUID createdBy,
    String importUnmatchedJson,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {}

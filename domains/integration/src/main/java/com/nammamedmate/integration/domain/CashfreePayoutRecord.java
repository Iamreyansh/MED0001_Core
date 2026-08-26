package com.nammamedmate.integration.domain;

import java.time.Instant;
import java.util.UUID;

public record CashfreePayoutRecord(
    UUID id,
    String entityType,
    UUID entityId,
    String beneficiaryId,
    String cashfreeTransferId,
    String referenceId,
    long amountPaise,
    String mode,
    String status,
    int retryCount,
    Instant initiatedAt,
    Instant processedAt,
    String failureReason) {}

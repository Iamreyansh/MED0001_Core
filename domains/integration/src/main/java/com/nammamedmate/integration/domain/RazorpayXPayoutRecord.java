package com.nammamedmate.integration.domain;

import java.time.Instant;
import java.util.UUID;

public record RazorpayXPayoutRecord(
    UUID id,
    String entityType,
    UUID entityId,
    String fundAccountId,
    String razorpayxPayoutId,
    String referenceId,
    long amountPaise,
    String mode,
    String status,
    int retryCount,
    Instant initiatedAt,
    Instant processedAt,
    String failureReason) {}

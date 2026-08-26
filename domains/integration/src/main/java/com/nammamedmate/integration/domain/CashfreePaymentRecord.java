package com.nammamedmate.integration.domain;

import java.time.Instant;
import java.util.UUID;

public record CashfreePaymentRecord(
    UUID id,
    UUID platformOrderId,
    String gatewayOrderId,
    String gatewayPaymentId,
    int amountPaise,
    String currency,
    String paymentMethod,
    String status,
    Instant createdAt,
    Instant capturedAt) {}

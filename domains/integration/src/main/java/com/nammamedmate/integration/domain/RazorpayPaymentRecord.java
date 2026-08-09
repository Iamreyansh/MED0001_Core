package com.nammamedmate.integration.domain;

import java.time.Instant;
import java.util.UUID;

public record RazorpayPaymentRecord(
    UUID id,
    UUID platformOrderId,
    String razorpayOrderId,
    String razorpayPaymentId,
    int amountPaise,
    String currency,
    String paymentMethod,
    String status,
    Instant createdAt,
    Instant capturedAt) {}

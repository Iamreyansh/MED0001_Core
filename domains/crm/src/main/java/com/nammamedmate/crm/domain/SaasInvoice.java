package com.nammamedmate.crm.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SaasInvoice(
    UUID id,
    String invoiceNumber,
    UUID accountId,
    UUID subscriptionId,
    String planName,
    LocalDate billingPeriodFrom,
    LocalDate billingPeriodTo,
    long subtotalPaise,
    BigDecimal gstRatePct,
    long gstAmountPaise,
    long totalAmountPaise,
    String status,
    LocalDate dueAt,
    Instant paidAt,
    String paymentMode,
    String referenceNumber,
    UUID markedPaidBy,
    int dunningStep,
    String waiveReason,
    String pdfObjectKey,
    String checkoutUrl,
    Instant checkoutExpiresAt,
    String markPaidIdempotencyKey,
    String payIdempotencyKey,
    Instant createdAt,
    Instant updatedAt) {}

package com.nammamedmate.pos.domain;

import java.time.Instant;
import java.util.UUID;

public record Invoice(
    UUID id,
    UUID pharmacyId,
    String invoiceNumber,
    UUID cartId,
    InvoiceChannel channel,
    UUID customerId,
    String customerName,
    String customerPhone,
    String prescribingDoctor,
    long subtotalPaise,
    long discountAmountPaise,
    long gstTotalPaise,
    long grandTotalPaise,
    PaymentMethod paymentMethod,
    PaymentStatus paymentStatus,
    String paymentReference,
    long amountPaidPaise,
    long changeDuePaise,
    long mrpSavingsPaise,
    InvoiceStatus status,
    String invoicePdfUrl,
    Instant createdAt) {}

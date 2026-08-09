package com.nammamedmate.pos.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PosCart(
    UUID id,
    UUID pharmacyId,
    UUID staffId,
    UUID customerId,
    String customerName,
    String customerPhone,
    String prescribingDoctor,
    DiscountType discountType,
    BigDecimal discountValue,
    long discountAmountPaise,
    long subtotalPaise,
    long gstTotalPaise,
    long grandTotalPaise,
    PosCartStatus status,
    Instant expiresAt,
    UUID invoiceId,
    UUID appliedOfferId,
    Instant createdAt,
    Instant updatedAt) {}

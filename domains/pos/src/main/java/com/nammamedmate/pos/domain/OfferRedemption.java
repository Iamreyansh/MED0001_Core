package com.nammamedmate.pos.domain;

import java.time.Instant;
import java.util.UUID;

public record OfferRedemption(
    UUID id,
    UUID offerId,
    UUID pharmacyId,
    UUID invoiceId,
    UUID customerId,
    long discountAmountPaise,
    OfferRedemptionChannel channel,
    Instant createdAt) {}

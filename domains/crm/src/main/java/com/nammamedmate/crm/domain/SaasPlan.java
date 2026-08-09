package com.nammamedmate.crm.domain;

import java.time.Instant;
import java.util.UUID;

public record SaasPlan(
    UUID id,
    String name,
    long priceMonthlyPaise,
    Integer seatLimit,
    Integer invoiceCapMonthly,
    boolean active,
    boolean customPricing,
    Instant updatedAt) {}

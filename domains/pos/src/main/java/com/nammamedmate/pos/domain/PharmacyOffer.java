package com.nammamedmate.pos.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PharmacyOffer(
    UUID id,
    UUID pharmacyId,
    String title,
    String couponCode,
    DiscountType discountType,
    long discountValue,
    OfferAppliesTo appliesTo,
    List<UUID> scopeIds,
    boolean online,
    boolean counter,
    boolean active,
    LocalDate validFrom,
    LocalDate validUntil,
    int maxRedemptions,
    int totalRedemptions,
    Instant createdAt,
    Instant updatedAt) {

  public PharmacyOffer {
    scopeIds = scopeIds == null ? List.of() : List.copyOf(scopeIds);
  }

  public boolean isExpired(LocalDate today) {
    return validUntil.isBefore(today);
  }
}

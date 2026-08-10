package com.nammamedmate.marketing.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Coupon(
    UUID id,
    String code,
    CouponType type,
    Integer percentValue,
    Long valuePaise,
    long minOrderValuePaise,
    Long maxDiscountCapPaise,
    long budgetTotalPaise,
    long budgetUsedPaise,
    int redemptionsCount,
    Integer maxRedemptionsTotal,
    int maxPerUser,
    List<UUID> segmentIds,
    boolean firstOrderOnly,
    boolean rxOrdersOnly,
    Instant validFrom,
    Instant validUntil,
    CouponStatus status,
    String description,
    String terms,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt) {

  public Coupon {
    segmentIds = segmentIds == null ? List.of() : List.copyOf(segmentIds);
  }

  public boolean openToAllSegments() {
    return segmentIds.isEmpty();
  }

  public Number apiValue() {
    return switch (type) {
      case PERCENTAGE -> percentValue == null ? Integer.valueOf(0) : percentValue;
      case FLAT_RS -> valuePaise == null ? 0 : MoneyFormats.paiseToRupees(valuePaise);
      case FREE_DELIVERY -> 0;
    };
  }
}

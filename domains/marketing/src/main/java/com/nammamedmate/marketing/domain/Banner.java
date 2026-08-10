package com.nammamedmate.marketing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

public record Banner(
    UUID id,
    String headline,
    String subText,
    String imageUrl,
    BannerPlacement placement,
    BannerLinkType linkType,
    String linkValue,
    String themeColor,
    boolean live,
    Instant validFrom,
    Instant validUntil,
    int priority,
    long impressions,
    long clicks,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt) {

  /** CTR percentage: (clicks / impressions) * 100, or 0 when no impressions. */
  public BigDecimal ctrPct() {
    if (impressions <= 0) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(clicks)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(impressions), 1, RoundingMode.HALF_UP);
  }

  public boolean activeAt(Instant now) {
    return live && !now.isBefore(validFrom) && !now.isAfter(validUntil);
  }

  public String statusLabel() {
    return live ? "LIVE" : "OFFLINE";
  }
}

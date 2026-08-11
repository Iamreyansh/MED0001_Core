package com.nammamedmate.notification.domain;

import java.math.BigDecimal;

public enum WhatsAppCategory {
  UTILITY(new BigDecimal("0.85")),
  MARKETING(new BigDecimal("2.00")),
  AUTHENTICATION(new BigDecimal("0.85"));

  private final BigDecimal costRs;

  WhatsAppCategory(BigDecimal costRs) {
    this.costRs = costRs;
  }

  public BigDecimal costRs() {
    return costRs;
  }

  public static WhatsAppCategory parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("INVALID_CATEGORY");
    }
    try {
      return WhatsAppCategory.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_CATEGORY");
    }
  }
}

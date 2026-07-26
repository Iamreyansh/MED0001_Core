package com.nammamedmate.customer.domain;

import java.util.Locale;

public enum CustomerSegment {
  NEW,
  REGULAR,
  LOYAL,
  VIP;

  public static CustomerSegment parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return CustomerSegment.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid segment: " + raw);
    }
  }

  /**
   * Highest-wins from order count and LTV (paise). Thresholds: VIP ≥50 orders or ≥₹25,000; LOYAL
   * ≥12 or ≥₹5,000; REGULAR ≥1 order; else NEW.
   */
  public static CustomerSegment compute(int totalOrders, long totalLtvPaise) {
    if (totalOrders >= 50 || totalLtvPaise >= 2_500_000L) {
      return VIP;
    }
    if (totalOrders >= 12 || totalLtvPaise >= 500_000L) {
      return LOYAL;
    }
    if (totalOrders >= 1) {
      return REGULAR;
    }
    return NEW;
  }
}

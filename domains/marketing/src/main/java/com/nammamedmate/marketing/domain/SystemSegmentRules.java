package com.nammamedmate.marketing.domain;

import java.time.Instant;
import java.util.Set;

/**
 * SYSTEM segment membership rules. Garbled story operators interpreted as: NEW total_orders ≤ 1;
 * VIP total_orders ≥ 30 OR ltv &gt; Rs 10,000.
 */
public final class SystemSegmentRules {

  public static final long VIP_LTV_PAISE = 1_000_000L; // Rs 10,000

  private SystemSegmentRules() {}

  public static boolean matches(
      String segmentName, CustomerMetrics m, Instant now, Set<String> highValuePincodes) {
    return switch (segmentName) {
      case "NEW" -> m.totalOrders() <= 1 && m.accountAgeDays() < 7;
      case "REGULAR" -> m.totalOrders() >= 2 && m.totalOrders() <= 9;
      case "LOYAL" -> {
        boolean mid = m.totalOrders() >= 10 && m.totalOrders() <= 29;
        yield mid || m.ordersLast30Days() >= 3;
      }
      case "VIP" -> m.totalOrders() >= 30 || m.ltvPaise() > VIP_LTV_PAISE;
      case "DORMANT" -> m.lastOrderDaysAgo(now) >= 60;
      case "RX_USERS" -> m.hasRxOrders();
      case "HIGH_VALUE_AREA" -> {
        if (highValuePincodes == null || highValuePincodes.isEmpty()) {
          yield false;
        }
        String pin = m.pincode() == null ? "" : m.pincode().trim();
        yield highValuePincodes.contains(pin);
      }
      case "ALL" -> true;
      default -> false;
    };
  }
}

package com.nammamedmate.marketing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Customer aggregates used when evaluating segment membership. */
public record CustomerMetrics(
    UUID customerId,
    String name,
    String phone,
    int totalOrders,
    long ltvPaise,
    Instant lastOrderAt,
    long avgAovPaise,
    boolean hasRxOrders,
    int accountAgeDays,
    int ordersLast30Days,
    String city,
    String pincode,
    String loyaltyTier) {

  public String loyaltyTierOrNone() {
    return loyaltyTier == null || loyaltyTier.isBlank()
        ? "NONE"
        : loyaltyTier.trim().toUpperCase(Locale.ROOT);
  }

  public int lastOrderDaysAgo(Instant now) {
    if (lastOrderAt == null) {
      return Integer.MAX_VALUE;
    }
    long days = java.time.Duration.between(lastOrderAt, now).toDays();
    return (int) Math.max(0, days);
  }

  /** AND-match all custom criteria. */
  public boolean matchesAll(List<SegmentCriterion> criteria, Instant now) {
    if (criteria == null || criteria.isEmpty()) {
      return false;
    }
    for (SegmentCriterion c : criteria) {
      if (!matchesOne(c, now)) {
        return false;
      }
    }
    return true;
  }

  public boolean matchesOne(SegmentCriterion c, Instant now) {
    return switch (c.field()) {
      case "total_orders" -> compareInt(totalOrders, c.operator(), c.value());
      case "ltv_rs" -> compareMoney(ltvPaise, c.operator(), c.value());
      case "last_order_days_ago" -> compareInt(lastOrderDaysAgo(now), c.operator(), c.value());
      case "avg_order_value_rs" -> compareMoney(avgAovPaise, c.operator(), c.value());
      case "city" -> matchStringSet(city, c.operator(), c.value());
      case "pincode" -> matchStringSet(pincode, c.operator(), c.value());
      case "has_rx_orders" -> hasRxOrders == toBoolean(c.value());
      case "loyalty_tier" -> matchStringSet(loyaltyTierOrNone(), "in", c.value());
      default -> false;
    };
  }

  private static boolean compareInt(int actual, String op, Object raw) {
    if ("between".equals(op)) {
      List<?> bounds = (List<?>) raw;
      int lo = toInt(bounds.get(0));
      int hi = toInt(bounds.get(1));
      if (actual < lo) {
        return false;
      }
      return actual <= hi;
    }
    int expected = toInt(raw);
    return switch (op) {
      case "=" -> actual == expected;
      case ">" -> actual > expected;
      case "<" -> actual < expected;
      case ">=" -> actual >= expected;
      case "<=" -> actual <= expected;
      default -> false;
    };
  }

  private static boolean compareMoney(long actualPaise, String op, Object raw) {
    if ("between".equals(op)) {
      List<?> bounds = (List<?>) raw;
      long lo = MoneyFormats.criterionRupeesToPaise(bounds.get(0));
      long hi = MoneyFormats.criterionRupeesToPaise(bounds.get(1));
      if (actualPaise < lo) {
        return false;
      }
      return actualPaise <= hi;
    }
    long expectedPaise = MoneyFormats.criterionRupeesToPaise(raw);
    if ("=".equals(op)) {
      return actualPaise == expectedPaise;
    }
    if (">".equals(op)) {
      return actualPaise > expectedPaise;
    }
    if ("<".equals(op)) {
      return actualPaise < expectedPaise;
    }
    if (">=".equals(op)) {
      return actualPaise >= expectedPaise;
    }
    if ("<=".equals(op)) {
      return actualPaise <= expectedPaise;
    }
    return false;
  }

  private static boolean matchStringSet(String actual, String op, Object raw) {
    Set<String> wanted =
        ((List<?>) raw)
            .stream()
                .map(v -> String.valueOf(v).trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
    String norm = actual == null ? "" : actual.trim().toUpperCase(Locale.ROOT);
    boolean contained = wanted.contains(norm);
    return "in".equals(op) ? contained : !contained;
  }

  private static int toInt(Object raw) {
    if (raw instanceof Number n) {
      return n.intValue();
    }
    return Integer.parseInt(String.valueOf(raw).trim());
  }

  private static boolean toBoolean(Object raw) {
    if (raw instanceof Boolean b) {
      return b;
    }
    return Boolean.parseBoolean(String.valueOf(raw).trim());
  }
}

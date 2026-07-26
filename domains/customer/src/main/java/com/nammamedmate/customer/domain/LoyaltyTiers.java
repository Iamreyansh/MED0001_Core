package com.nammamedmate.customer.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Lifetime-points tier ladder (one-way ratchet). NONE → SILVER → GOLD → PLATINUM. */
public final class LoyaltyTiers {

  public static final String NONE = "NONE";
  public static final String SILVER = "SILVER";
  public static final String GOLD = "GOLD";
  public static final String PLATINUM = "PLATINUM";

  private static final int SILVER_MIN = 12;
  private static final int GOLD_MIN = 50;
  private static final int PLATINUM_MIN = 120;

  private LoyaltyTiers() {}

  /** Tier from lifetime points earned (never drops on reverse). */
  public static String fromLifetimePoints(int pointsEarnedLifetime) {
    int pts = Math.max(0, pointsEarnedLifetime);
    if (pts >= PLATINUM_MIN) {
      return PLATINUM;
    }
    if (pts >= GOLD_MIN) {
      return GOLD;
    }
    if (pts >= SILVER_MIN) {
      return SILVER;
    }
    return NONE;
  }

  /**
   * @deprecated use {@link #fromLifetimePoints(int)} — kept for call-site migration.
   */
  @Deprecated
  public static String fromPoints(int points) {
    return fromLifetimePoints(points);
  }

  public static String nextTier(String currentTier) {
    return switch (normalize(currentTier)) {
      case NONE -> SILVER;
      case SILVER -> GOLD;
      case GOLD -> PLATINUM;
      default -> null;
    };
  }

  public static int minForTier(String tier) {
    return switch (normalize(tier)) {
      case SILVER -> SILVER_MIN;
      case GOLD -> GOLD_MIN;
      case PLATINUM -> PLATINUM_MIN;
      default -> 0;
    };
  }

  public static Integer maxForTier(String tier) {
    return switch (normalize(tier)) {
      case NONE -> SILVER_MIN - 1;
      case SILVER -> GOLD_MIN - 1;
      case GOLD -> PLATINUM_MIN - 1;
      default -> null;
    };
  }

  /**
   * Progress toward next tier based on lifetime points. At PLATINUM, next is null and progress_pct
   * is 100.
   */
  public static Map<String, Object> progress(int pointsEarnedLifetime) {
    int pts = Math.max(0, pointsEarnedLifetime);
    String current = fromLifetimePoints(pts);
    String next = nextTier(current);
    Map<String, Object> progress = new LinkedHashMap<>();
    progress.put("current_tier", current);
    progress.put("next_tier", next);
    if (next == null) {
      progress.put("points_for_next_tier", null);
      progress.put("points_needed", 0);
      progress.put("progress_pct", 100);
      return progress;
    }
    int nextMin = minForTier(next);
    int currentMin = minForTier(current);
    int needed = Math.max(0, nextMin - pts);
    int span = Math.max(1, nextMin - currentMin);
    int gained = Math.min(span, Math.max(0, pts - currentMin));
    int pct = (int) Math.round((gained * 100.0) / span);
    progress.put("points_for_next_tier", nextMin);
    progress.put("points_needed", needed);
    progress.put("progress_pct", Math.min(100, Math.max(0, pct)));
    return progress;
  }

  public static Map<String, Object> thresholds() {
    Map<String, Object> all = new LinkedHashMap<>();
    all.put(NONE, range(0, SILVER_MIN - 1));
    all.put(SILVER, range(SILVER_MIN, GOLD_MIN - 1));
    all.put(GOLD, range(GOLD_MIN, PLATINUM_MIN - 1));
    all.put(PLATINUM, range(PLATINUM_MIN, null));
    return all;
  }

  /** Points for an order: floor(rupees / 100). Order value in paise. */
  public static int pointsForOrderPaise(long orderTotalPaise) {
    if (orderTotalPaise <= 0) {
      return 0;
    }
    long rupees = orderTotalPaise / 100;
    return (int) (rupees / 100);
  }

  private static Map<String, Object> range(int min, Integer max) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("min", min);
    r.put("max", max);
    return r;
  }

  private static String normalize(String tier) {
    if (tier == null || tier.isBlank()) {
      return NONE;
    }
    return tier.trim().toUpperCase(Locale.ROOT);
  }
}

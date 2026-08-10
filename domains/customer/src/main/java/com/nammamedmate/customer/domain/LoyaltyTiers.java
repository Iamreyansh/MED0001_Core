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
  private static final int DEFAULT_EARN_RATE_RS = 100;

  private LoyaltyTiers() {}

  /** Tier from lifetime points earned (never drops on reverse). Default story thresholds. */
  public static String fromLifetimePoints(int pointsEarnedLifetime) {
    return fromLifetimePoints(pointsEarnedLifetime, SILVER_MIN, GOLD_MIN, PLATINUM_MIN);
  }

  public static String fromLifetimePoints(
      int pointsEarnedLifetime, int silverMin, int goldMin, int platinumMin) {
    int pts = Math.max(0, pointsEarnedLifetime);
    int silver = Math.max(1, silverMin);
    int gold = Math.max(silver + 1, goldMin);
    int platinum = Math.max(gold + 1, platinumMin);
    if (pts >= platinum) {
      return PLATINUM;
    }
    if (pts >= gold) {
      return GOLD;
    }
    if (pts >= silver) {
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
    return minForTier(tier, SILVER_MIN, GOLD_MIN, PLATINUM_MIN);
  }

  public static int minForTier(String tier, int silverMin, int goldMin, int platinumMin) {
    return switch (normalize(tier)) {
      case SILVER -> Math.max(1, silverMin);
      case GOLD -> Math.max(Math.max(1, silverMin) + 1, goldMin);
      case PLATINUM -> Math.max(Math.max(Math.max(1, silverMin) + 1, goldMin) + 1, platinumMin);
      default -> 0;
    };
  }

  public static Integer maxForTier(String tier) {
    return maxForTier(tier, SILVER_MIN, GOLD_MIN, PLATINUM_MIN);
  }

  public static Integer maxForTier(String tier, int silverMin, int goldMin, int platinumMin) {
    int silver = Math.max(1, silverMin);
    int gold = Math.max(silver + 1, goldMin);
    int platinum = Math.max(gold + 1, platinumMin);
    return switch (normalize(tier)) {
      case NONE -> silver - 1;
      case SILVER -> gold - 1;
      case GOLD -> platinum - 1;
      default -> null;
    };
  }

  public static Map<String, Object> progress(int pointsEarnedLifetime) {
    return progress(pointsEarnedLifetime, SILVER_MIN, GOLD_MIN, PLATINUM_MIN);
  }

  public static Map<String, Object> progress(
      int pointsEarnedLifetime, int silverMin, int goldMin, int platinumMin) {
    int pts = Math.max(0, pointsEarnedLifetime);
    String current = fromLifetimePoints(pts, silverMin, goldMin, platinumMin);
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
    int nextMin = minForTier(next, silverMin, goldMin, platinumMin);
    int currentMin = minForTier(current, silverMin, goldMin, platinumMin);
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
    return thresholds(SILVER_MIN, GOLD_MIN, PLATINUM_MIN);
  }

  /** EPIC-002 response shape: each tier → {min, max}. */
  public static Map<String, Object> thresholds(int silverMin, int goldMin, int platinumMin) {
    int silver = Math.max(1, silverMin);
    int gold = Math.max(silver + 1, goldMin);
    int platinum = Math.max(gold + 1, platinumMin);
    Map<String, Object> all = new LinkedHashMap<>();
    all.put(NONE, range(0, silver - 1));
    all.put(SILVER, range(silver, gold - 1));
    all.put(GOLD, range(gold, platinum - 1));
    all.put(PLATINUM, range(platinum, null));
    return all;
  }

  /** Points for an order: floor(rupees / earnRateRsPerPoint). Order value in paise. */
  public static int pointsForOrderPaise(long orderTotalPaise) {
    return pointsForOrderPaise(orderTotalPaise, DEFAULT_EARN_RATE_RS);
  }

  public static int pointsForOrderPaise(long orderTotalPaise, int earnRateRsPerPoint) {
    if (orderTotalPaise <= 0) {
      return 0;
    }
    int rate = Math.max(1, earnRateRsPerPoint);
    long rupees = orderTotalPaise / 100;
    return (int) (rupees / rate);
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

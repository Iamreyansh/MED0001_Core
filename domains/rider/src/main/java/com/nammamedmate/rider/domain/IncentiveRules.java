package com.nammamedmate.rider.domain;

import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;

/**
 * Streak / threshold helpers for STORY-008.
 *
 * <p>ponytail: trip-level peak-hour / trip-count incentives deferred to EPIC-015 Automation Engine
 * — this story stores {@code incentive_bonus_paise} and applies streak bonus only.
 */
public final class IncentiveRules {

  public static final long DEFAULT_STREAK_BONUS_PAISE = 10_000L;
  public static final int DEFAULT_STREAK_DAYS = 7;
  public static final long DEFAULT_MIN_PAYOUT_PAISE = 10_000L;
  public static final BigDecimalPct DEFAULT_ACCEPTANCE_ALERT = new BigDecimalPct(70);

  public static final String KEY_STREAK_BONUS = "rider_streak_bonus_paise";
  public static final String KEY_STREAK_DAYS = "rider_streak_days_required";
  public static final String KEY_MIN_PAYOUT = "rider_min_payout_paise";
  public static final String KEY_ACCEPTANCE_ALERT = "rider_acceptance_alert_threshold_pct";

  /** Tiny holder so callers don't import BigDecimal for a constant int threshold. */
  public record BigDecimalPct(int value) {}

  private IncentiveRules() {}

  /** Per-trip incentive from Automation Engine — stub returns 0 until EPIC-015. */
  public static long tripIncentiveBonusPaise() {
    return 0L;
  }

  public static long streakBonusPaise(PlatformPricingConfigStore config) {
    return longConfig(config, KEY_STREAK_BONUS, DEFAULT_STREAK_BONUS_PAISE);
  }

  public static int streakDaysRequired(PlatformPricingConfigStore config) {
    return (int) longConfig(config, KEY_STREAK_DAYS, DEFAULT_STREAK_DAYS);
  }

  public static long minPayoutPaise(PlatformPricingConfigStore config) {
    return longConfig(config, KEY_MIN_PAYOUT, DEFAULT_MIN_PAYOUT_PAISE);
  }

  public static int acceptanceAlertThresholdPct(PlatformPricingConfigStore config) {
    return (int) longConfig(config, KEY_ACCEPTANCE_ALERT, DEFAULT_ACCEPTANCE_ALERT.value());
  }

  private static long longConfig(PlatformPricingConfigStore config, String key, long def) {
    if (config == null) {
      return def;
    }
    return config
        .get(key)
        .map(
            raw -> {
              try {
                return Long.parseLong(raw.trim());
              } catch (RuntimeException e) {
                return def;
              }
            })
        .orElse(def);
  }
}

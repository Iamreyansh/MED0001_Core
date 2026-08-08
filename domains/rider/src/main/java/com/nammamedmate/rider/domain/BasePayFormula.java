package com.nammamedmate.rider.domain;

import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** BR-001 / AC-001: distance-based rider base pay (paise). */
public final class BasePayFormula {

  public static final long DEFAULT_MIN_PAISE = 1_500L;
  public static final long DEFAULT_MAX_PAISE = 2_500L;
  public static final BigDecimal DEFAULT_MIN_KM = new BigDecimal("2.0");
  public static final BigDecimal DEFAULT_MAX_KM = new BigDecimal("5.0");

  public static final String KEY_MIN_PAISE = "rider_base_pay_min_paise";
  public static final String KEY_MAX_PAISE = "rider_base_pay_max_paise";
  public static final String KEY_MIN_KM = "rider_base_pay_min_km";
  public static final String KEY_MAX_KM = "rider_base_pay_max_km";

  private BasePayFormula() {}

  public static long computePaise(BigDecimal distanceKm) {
    return computePaise(
        distanceKm, DEFAULT_MIN_PAISE, DEFAULT_MAX_PAISE, DEFAULT_MIN_KM, DEFAULT_MAX_KM);
  }

  public static long computePaise(BigDecimal distanceKm, PlatformPricingConfigStore config) {
    long minPaise = longConfig(config, KEY_MIN_PAISE, DEFAULT_MIN_PAISE);
    long maxPaise = longConfig(config, KEY_MAX_PAISE, DEFAULT_MAX_PAISE);
    BigDecimal minKm = decimalConfig(config, KEY_MIN_KM, DEFAULT_MIN_KM);
    BigDecimal maxKm = decimalConfig(config, KEY_MAX_KM, DEFAULT_MAX_KM);
    return computePaise(distanceKm, minPaise, maxPaise, minKm, maxKm);
  }

  public static long computePaise(
      BigDecimal distanceKm, long minPaise, long maxPaise, BigDecimal minKm, BigDecimal maxKm) {
    BigDecimal km = distanceKm == null ? BigDecimal.ZERO : distanceKm.max(BigDecimal.ZERO);
    if (km.compareTo(minKm) <= 0) {
      return minPaise;
    }
    if (km.compareTo(maxKm) >= 0) {
      return maxPaise;
    }
    BigDecimal span = maxKm.subtract(minKm);
    // minKm < km < maxKm implies span > 0 after the guards above.
    BigDecimal ratio = km.subtract(minKm).divide(span, 8, RoundingMode.HALF_UP);
    BigDecimal pay =
        BigDecimal.valueOf(minPaise).add(BigDecimal.valueOf(maxPaise - minPaise).multiply(ratio));
    return pay.setScale(0, RoundingMode.HALF_UP).longValueExact();
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

  private static BigDecimal decimalConfig(
      PlatformPricingConfigStore config, String key, BigDecimal def) {
    if (config == null) {
      return def;
    }
    return config
        .get(key)
        .map(
            raw -> {
              try {
                return new BigDecimal(raw.trim());
              } catch (RuntimeException e) {
                return def;
              }
            })
        .orElse(def);
  }
}

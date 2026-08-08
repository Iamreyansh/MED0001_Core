package com.nammamedmate.rider.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Acceptance / cancel / on-time percentage helpers (AC-008). */
public final class PerformanceRates {

  private PerformanceRates() {}

  /**
   * Story text says {@code (accepted/assigned) - 100}; example values are percentages, so this is
   * {@code (accepted/assigned) * 100}.
   */
  public static BigDecimal ratePct(long numerator, long denominator) {
    if (denominator <= 0) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(numerator)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
  }

  public static double ratePctDouble(long numerator, long denominator) {
    return ratePct(numerator, denominator).doubleValue();
  }
}

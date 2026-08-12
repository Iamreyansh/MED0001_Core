package com.nammamedmate.observability_ops.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * SLO error-budget helpers.
 *
 * <p>Story BR-10 text ({@code 100 - ((target-current)/(100-target) - 100)}) is algebraically
 * inconsistent with the sample payloads. We use the standard SRE form that matches payment /
 * dispatch / api_p99 samples: {@code remaining = 100 - ((target - current) / max(100 - target, 1) *
 * 100)}. order_sla sample remaining 74 vs computed 64 is treated as a sample typo.
 */
public final class ErrorBudget {

  private ErrorBudget() {}

  public static BigDecimal remainingPct(BigDecimal targetPct, BigDecimal currentPct) {
    BigDecimal target = targetPct == null ? BigDecimal.ZERO : targetPct;
    BigDecimal current = currentPct == null ? BigDecimal.ZERO : currentPct;
    BigDecimal denom = BigDecimal.valueOf(100).subtract(target);
    if (denom.compareTo(BigDecimal.ZERO) <= 0) {
      denom = BigDecimal.ONE;
    }
    BigDecimal consumed =
        target
            .subtract(current)
            .divide(denom, 8, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
    return BigDecimal.valueOf(100).subtract(consumed).setScale(1, RoundingMode.HALF_UP);
  }

  public static boolean exhausted(BigDecimal remainingPct) {
    return remainingPct != null && remainingPct.compareTo(BigDecimal.ZERO) <= 0;
  }

  /** Consumed % of error budget; negative means headroom above target. */
  public static BigDecimal consumedPct(BigDecimal targetPct, BigDecimal currentPct) {
    return BigDecimal.valueOf(100)
        .subtract(remainingPct(targetPct, currentPct))
        .setScale(1, RoundingMode.HALF_UP);
  }
}

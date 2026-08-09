package com.nammamedmate.crm.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Churn / renewal arithmetic (EPIC-014 STORY-007). */
public final class ChurnMath {

  private ChurnMath() {}

  /** logo_churn_pct = (churned_logos / start_of_period_logos) × 100 */
  public static BigDecimal logoChurnPct(long churnedLogos, long startOfPeriodLogos) {
    if (startOfPeriodLogos <= 0) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(churnedLogos)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(startOfPeriodLogos), 2, RoundingMode.HALF_UP);
  }

  public static BigDecimal pctOf(long part, long total) {
    if (total <= 0) {
      return BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(part)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(total), 3, RoundingMode.HALF_UP);
  }
}

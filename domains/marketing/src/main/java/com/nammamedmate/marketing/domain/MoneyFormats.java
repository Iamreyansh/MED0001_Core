package com.nammamedmate.marketing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MoneyFormats {

  private MoneyFormats() {}

  public static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
  }

  public static long rupeesToPaise(BigDecimal rupees) {
    return rupees.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
  }

  /** Convert criterion API value (Rs) to paise for comparison against stored metrics. */
  public static long criterionRupeesToPaise(Object value) {
    if (value instanceof Number n) {
      return BigDecimal.valueOf(n.doubleValue())
          .movePointRight(2)
          .setScale(0, RoundingMode.HALF_UP)
          .longValue();
    }
    if (value instanceof String s) {
      return new BigDecimal(s.trim())
          .movePointRight(2)
          .setScale(0, RoundingMode.HALF_UP)
          .longValue();
    }
    throw new IllegalArgumentException("expected numeric rupees value");
  }
}

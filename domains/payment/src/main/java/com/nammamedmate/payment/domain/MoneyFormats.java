package com.nammamedmate.payment.domain;

import com.nammamedmate.kernel.error.AppException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Paise ↔ rupees presentation helpers for payment API envelopes. */
public final class MoneyFormats {

  private MoneyFormats() {}

  public static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
  }

  /** Parse story rupee amount ({@code 50.00}) to paise; throws {@code INVALID_AMOUNT}. */
  public static long parsePositiveRupeesToPaise(Object amount) {
    if (amount == null) {
      throw new AppException("INVALID_AMOUNT", "amount must be > 0", 422);
    }
    BigDecimal value;
    if (amount instanceof BigDecimal bd) {
      value = bd;
    } else if (amount instanceof Number n) {
      value = BigDecimal.valueOf(n.doubleValue());
    } else if (amount instanceof String s) {
      try {
        value = new BigDecimal(s.trim());
      } catch (NumberFormatException ex) {
        throw new AppException("INVALID_AMOUNT", "amount must be > 0", 422);
      }
    } else {
      throw new AppException("INVALID_AMOUNT", "amount must be > 0", 422);
    }
    if (value.scale() > 2) {
      throw new AppException("INVALID_AMOUNT", "amount may have at most 2 decimal places", 422);
    }
    if (value.compareTo(BigDecimal.ZERO) <= 0) {
      throw new AppException("INVALID_AMOUNT", "amount must be > 0", 422);
    }
    return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
  }
}

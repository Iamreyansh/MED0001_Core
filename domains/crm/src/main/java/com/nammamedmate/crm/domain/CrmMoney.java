package com.nammamedmate.crm.domain;

import com.nammamedmate.kernel.error.AppException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Paise ↔ rupees helpers at the CRM adapter boundary. */
public final class CrmMoney {

  private CrmMoney() {}

  public static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
  }

  /** Annual = monthly × 10 (2 months free). */
  public static long annualPaise(long monthlyPaise) {
    return Math.multiplyExact(monthlyPaise, 10L);
  }

  public static BigDecimal annualSavingsPct() {
    return BigDecimal.valueOf(16.7).setScale(1, RoundingMode.HALF_UP);
  }

  public static long rupeesToPaise(BigDecimal rupees) {
    if (rupees == null) {
      throw new AppException("VALIDATION_ERROR", "price_monthly_rs required", 422);
    }
    if (rupees.scale() > 2) {
      throw new AppException("VALIDATION_ERROR", "price may have at most 2 decimal places", 422);
    }
    if (rupees.compareTo(BigDecimal.ZERO) < 0) {
      throw new AppException("VALIDATION_ERROR", "price must be >= 0", 422);
    }
    return rupees.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
  }

  /**
   * Prorated mid-cycle credit for remaining days in the calendar month (Asia/Kolkata day
   * boundaries).
   */
  public static long proratedCreditPaise(long monthlyPaise, int dayOfMonth, int daysInMonth) {
    if (monthlyPaise <= 0 || daysInMonth <= 0 || dayOfMonth < 1 || dayOfMonth > daysInMonth) {
      return 0L;
    }
    int unusedDays = daysInMonth - dayOfMonth;
    if (unusedDays <= 0) {
      return 0L;
    }
    return Math.multiplyExact(monthlyPaise, unusedDays) / daysInMonth;
  }

  /** attach_rate_pct = (accounts_with_addon / total_active) × 100 */
  public static BigDecimal attachRatePct(long accountsWithAddon, long totalActive) {
    if (totalActive <= 0) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(accountsWithAddon)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(totalActive), 1, RoundingMode.HALF_UP);
  }
}

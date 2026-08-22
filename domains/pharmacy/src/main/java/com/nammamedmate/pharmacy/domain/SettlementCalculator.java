package com.nammamedmate.pharmacy.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** TCS 1% of settlement GMV for all pharmacies. ₹5L threshold is TDS 194-O only (D6). */
public final class SettlementCalculator {

  /** Rs 5,00,000 annual GMV threshold (paise) — TDS 194-O, not TCS. */
  public static final long TCS_THRESHOLD_PAISE = 50_000_000L;

  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final BigDecimal TCS_RATE = new BigDecimal("1.00");

  private SettlementCalculator() {}

  public record Amounts(
      long commissionEarnedPaise,
      long tcsDeductedPaise,
      long netPaidPaise,
      boolean tcsApplicable,
      BigDecimal tcsRatePct) {}

  public static Amounts compute(long gmvPaise, BigDecimal commissionPct, long annualGmvYtdPaise) {
    long commissionEarned =
        BigDecimal.valueOf(gmvPaise)
            .multiply(commissionPct)
            .divide(HUNDRED, 0, RoundingMode.HALF_UP)
            .longValue();
    long tcsDeducted =
        BigDecimal.valueOf(gmvPaise)
            .multiply(TCS_RATE)
            .divide(HUNDRED, 0, RoundingMode.HALF_UP)
            .longValue();
    long netPaid = gmvPaise - commissionEarned - tcsDeducted;
    return new Amounts(commissionEarned, tcsDeducted, netPaid, true, TCS_RATE);
  }

  public static boolean tdsThresholdCrossed(long annualGmvYtdPaise) {
    return annualGmvYtdPaise > TCS_THRESHOLD_PAISE;
  }
}

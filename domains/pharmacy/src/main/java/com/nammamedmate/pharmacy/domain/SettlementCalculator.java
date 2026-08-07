package com.nammamedmate.pharmacy.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** TCS Section 194-O and net payout for pharmacy settlements. */
public final class SettlementCalculator {

  /** Rs 5,00,000 annual GMV threshold (paise). */
  public static final long TCS_THRESHOLD_PAISE = 50_000_000L;

  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final BigDecimal TCS_RATE = new BigDecimal("1.00");
  private static final BigDecimal ZERO_RATE = new BigDecimal("0.00");

  private SettlementCalculator() {}

  public record Amounts(
      long commissionEarnedPaise,
      long tcsDeductedPaise,
      long netPaidPaise,
      boolean tcsApplicable,
      BigDecimal tcsRatePct) {}

  public static Amounts compute(long gmvPaise, BigDecimal commissionPct, long annualGmvYtdPaise) {
    boolean tcsApplicable = annualGmvYtdPaise > TCS_THRESHOLD_PAISE;
    BigDecimal tcsRate = tcsApplicable ? TCS_RATE : ZERO_RATE;
    long commissionEarned =
        BigDecimal.valueOf(gmvPaise)
            .multiply(commissionPct)
            .divide(HUNDRED, 0, RoundingMode.HALF_UP)
            .longValue();
    long tcsDeducted =
        tcsApplicable
            ? BigDecimal.valueOf(gmvPaise)
                .multiply(TCS_RATE)
                .divide(HUNDRED, 0, RoundingMode.HALF_UP)
                .longValue()
            : 0L;
    long netPaid = gmvPaise - commissionEarned - tcsDeducted;
    return new Amounts(commissionEarned, tcsDeducted, netPaid, tcsApplicable, tcsRate);
  }
}

package com.nammamedmate.crm.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** SaaS revenue analytics formulas (EPIC-014 STORY-008). */
public final class AnalyticsMath {

  public static final BigDecimal DEFAULT_GROSS_MARGIN_PCT =
      BigDecimal.valueOf(72.0).setScale(1, RoundingMode.HALF_UP);

  private AnalyticsMath() {}

  public static long arrPaise(long mrrPaise) {
    return Math.multiplyExact(mrrPaise, 12L);
  }

  public static long netNewMrrPaise(
      long newMrr, long expansionMrr, long contractionMrr, long churnMrr) {
    return newMrr + expansionMrr - contractionMrr - churnMrr;
  }

  /**
   * NRR = (MRR_start + expansion − churn) / MRR_start × 100. Excludes new logos; contraction is not
   * deducted (locked STORY-008 definition).
   */
  public static BigDecimal nrrPct(long mrrStartPaise, long expansionMrrPaise, long churnMrrPaise) {
    if (mrrStartPaise <= 0L) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(mrrStartPaise + expansionMrrPaise - churnMrrPaise)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(mrrStartPaise), 2, RoundingMode.HALF_UP);
  }

  /** GRR = (MRR_start − churn) / MRR_start × 100. Always ≤ NRR when expansion ≥ 0. */
  public static BigDecimal grrPct(long mrrStartPaise, long churnMrrPaise) {
    if (mrrStartPaise <= 0L) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(mrrStartPaise - churnMrrPaise)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(mrrStartPaise), 2, RoundingMode.HALF_UP);
  }

  /** quick_ratio = (new + expansion) / (contraction + churn); 0 when denominator is 0. */
  public static BigDecimal quickRatio(
      long newMrr, long expansionMrr, long contractionMrr, long churnMrr) {
    long denom = contractionMrr + churnMrr;
    if (denom <= 0L) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(newMrr + expansionMrr)
        .divide(BigDecimal.valueOf(denom), 2, RoundingMode.HALF_UP);
  }

  /** magic_number = (QoQ MRR growth × 4) / prior-quarter S&M spend. */
  public static BigDecimal magicNumber(long qoqMrrGrowthPaise, long priorQuarterSmSpendPaise) {
    if (priorQuarterSmSpendPaise <= 0L) {
      return null;
    }
    return BigDecimal.valueOf(qoqMrrGrowthPaise)
        .multiply(BigDecimal.valueOf(4))
        .divide(BigDecimal.valueOf(priorQuarterSmSpendPaise), 2, RoundingMode.HALF_UP);
  }

  public static long arpaPaise(long mrrPaise, long payingAccounts) {
    if (payingAccounts <= 0L) {
      return 0L;
    }
    return mrrPaise / payingAccounts;
  }

  /**
   * LTV = ARPA × gross_margin / monthly_churn_rate. {@code logoChurnPct} is 0–100; margin is 0–100.
   */
  public static long ltvPaise(long arpaPaise, BigDecimal grossMarginPct, BigDecimal logoChurnPct) {
    if (arpaPaise <= 0L
        || logoChurnPct == null
        || logoChurnPct.compareTo(BigDecimal.ZERO) <= 0
        || grossMarginPct == null
        || grossMarginPct.compareTo(BigDecimal.ZERO) <= 0) {
      return 0L;
    }
    BigDecimal margin = grossMarginPct.movePointLeft(2);
    BigDecimal churnRate = logoChurnPct.movePointLeft(2);
    return BigDecimal.valueOf(arpaPaise)
        .multiply(margin)
        .divide(churnRate, 0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  public static long cacPaise(long smSpendPaise, long newLogos) {
    if (newLogos <= 0L || smSpendPaise <= 0L) {
      return 0L;
    }
    return smSpendPaise / newLogos;
  }

  public static BigDecimal ltvCacRatio(long ltvPaise, long cacPaise) {
    if (cacPaise <= 0L) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(ltvPaise)
        .divide(BigDecimal.valueOf(cacPaise), 1, RoundingMode.HALF_UP);
  }

  public static BigDecimal paybackMonths(long cacPaise, long arpaPaise, BigDecimal grossMarginPct) {
    if (cacPaise <= 0L
        || arpaPaise <= 0L
        || grossMarginPct == null
        || grossMarginPct.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    BigDecimal monthlyContribution =
        BigDecimal.valueOf(arpaPaise).multiply(grossMarginPct.movePointLeft(2));
    return BigDecimal.valueOf(cacPaise).divide(monthlyContribution, 1, RoundingMode.HALF_UP);
  }

  public static BigDecimal mrrGrowthPct(long currentMrrPaise, long priorMrrPaise) {
    if (priorMrrPaise <= 0L) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(currentMrrPaise - priorMrrPaise)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(priorMrrPaise), 1, RoundingMode.HALF_UP);
  }

  public static BigDecimal retentionPct(long retained, long starting) {
    if (starting <= 0L) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(retained)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(starting), 2, RoundingMode.HALF_UP);
  }
}

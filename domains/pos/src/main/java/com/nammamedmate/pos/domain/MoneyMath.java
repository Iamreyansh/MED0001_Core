package com.nammamedmate.pos.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** GST-inclusive MRP helpers and discount caps (paise). */
public final class MoneyMath {

  public static final long MAX_DISCOUNT_PAISE = 50_000L; // ₹500 manual cart
  public static final BigDecimal MAX_DISCOUNT_PCT = BigDecimal.valueOf(30);
  public static final long MAX_OFFER_DISCOUNT_PAISE = 100_000L; // ₹1000 offer
  public static final BigDecimal MAX_OFFER_DISCOUNT_PCT = BigDecimal.valueOf(50);

  private MoneyMath() {}

  /** Extract GST from inclusive line total: line - line*100/(100+gstPct). */
  public static long gstFromInclusive(long lineTotalPaise, int gstPct) {
    if (lineTotalPaise <= 0 || gstPct <= 0) {
      return 0L;
    }
    long taxable =
        BigDecimal.valueOf(lineTotalPaise)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(100 + gstPct), 0, RoundingMode.HALF_UP)
            .longValueExact();
    return lineTotalPaise - taxable;
  }

  public static long taxableFromInclusive(long lineTotalPaise, int gstPct) {
    return lineTotalPaise - gstFromInclusive(lineTotalPaise, gstPct);
  }

  public static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
  }

  public static long rupeesToPaise(BigDecimal rupees) {
    if (rupees == null) {
      throw new IllegalArgumentException("amount required");
    }
    return rupees.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
  }

  /** Cap = min(30% of subtotal, ₹500). */
  public static long maxDiscountPaise(long subtotalPaise) {
    long pctCap =
        BigDecimal.valueOf(subtotalPaise)
            .multiply(MAX_DISCOUNT_PCT)
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
            .longValueExact();
    return Math.min(pctCap, MAX_DISCOUNT_PAISE);
  }

  public static long computeDiscountAmountPaise(
      String discountType, BigDecimal discountValue, long subtotalPaise) {
    if (discountType == null || discountValue == null || subtotalPaise <= 0) {
      return 0L;
    }
    long amount;
    if ("PERCENTAGE".equals(discountType)) {
      amount =
          BigDecimal.valueOf(subtotalPaise)
              .multiply(discountValue)
              .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
              .longValueExact();
    } else if ("FLAT_RS".equals(discountType)) {
      amount = rupeesToPaise(discountValue);
    } else {
      return 0L;
    }
    return Math.min(amount, subtotalPaise);
  }

  /**
   * Offer discount: PERCENTAGE uses whole percent in {@code discountValue}; FLAT_RS uses paise in
   * {@code discountValue}.
   */
  public static long computeOfferDiscountPaise(
      DiscountType discountType, long discountValue, long eligibleSubtotalPaise) {
    if (discountType == null) {
      return 0L;
    }
    if (discountValue <= 0) {
      return 0L;
    }
    if (eligibleSubtotalPaise <= 0) {
      return 0L;
    }
    long amount =
        discountType == DiscountType.PERCENTAGE
            ? BigDecimal.valueOf(eligibleSubtotalPaise)
                .multiply(BigDecimal.valueOf(discountValue))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .longValueExact()
            : discountValue;
    return Math.min(amount, eligibleSubtotalPaise);
  }

  public static BigDecimal offerDiscountValueForApi(DiscountType type, long storedValue) {
    if (type == DiscountType.FLAT_RS) {
      return paiseToRupees(storedValue);
    }
    return BigDecimal.valueOf(storedValue);
  }
}

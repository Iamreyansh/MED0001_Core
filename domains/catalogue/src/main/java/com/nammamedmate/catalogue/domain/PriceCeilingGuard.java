package com.nammamedmate.catalogue.domain;

import com.nammamedmate.kernel.error.AppException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Checkout guard for EPIC-010 order placement. Rejects line items whose pharmacy online price
 * exceeds an active master catalogue MRP ceiling.
 */
public final class PriceCeilingGuard {

  private PriceCeilingGuard() {}

  public static void assertWithinCeiling(
      String medicineName, Long mrpCeilingPaise, long pharmacyPricePaise) {
    if (mrpCeilingPaise == null) {
      return;
    }
    if (pharmacyPricePaise <= mrpCeilingPaise) {
      return;
    }
    BigDecimal pharmacyRupees = paiseToRupees(pharmacyPricePaise);
    BigDecimal ceilingRupees = paiseToRupees(mrpCeilingPaise);
    String name = medicineName == null || medicineName.isBlank() ? "medicine" : medicineName.trim();
    throw new AppException(
        "PRICE_CEILING_VIOLATED",
        "This pharmacy's price for "
            + name
            + " (Rs "
            + pharmacyRupees
            + ") exceeds the platform ceiling (Rs "
            + ceilingRupees
            + "). Please choose another pharmacy.",
        400);
  }

  static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
  }
}

package com.nammamedmate.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GSTIN / phone / scheme helpers for distributor directory (STORY-005). */
public final class DistributorFormats {

  private static final Pattern GSTIN =
      Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$");
  private static final Pattern E164_INDIAN = Pattern.compile("^\\+91[6-9]\\d{9}$");
  private static final Pattern SCHEME =
      Pattern.compile("^(\\d+)\\s+free\\s+on\\s+(\\d+)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern EMAIL =
      Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

  private DistributorFormats() {}

  public static boolean isValidGstin(String gstin) {
    return gstin != null && GSTIN.matcher(gstin.trim().toUpperCase()).matches();
  }

  public static boolean isValidPhone(String phone) {
    return phone != null && E164_INDIAN.matcher(phone.trim()).matches();
  }

  public static boolean isValidEmail(String email) {
    return email != null && EMAIL.matcher(email.trim()).matches();
  }

  public static String schemeDescription(int freeQuantity, int paidQuantity) {
    if (freeQuantity <= 0) {
      return null;
    }
    return freeQuantity + " free on " + paidQuantity;
  }

  /**
   * effective_landed_cost = purchase_price - (free_goods_value / total_units) where
   * free_goods_value = free_qty × purchase_price and total_units = paid + free (from scheme "N free
   * on M").
   */
  public static BigDecimal effectiveLandedCostPaise(
      long purchasePricePaise, String schemeDescription) {
    int freeQty = 0;
    int paidQty = 1;
    if (schemeDescription != null && !schemeDescription.isBlank()) {
      Matcher m = SCHEME.matcher(schemeDescription.trim());
      if (m.matches()) {
        freeQty = Integer.parseInt(m.group(1));
        paidQty = Integer.parseInt(m.group(2));
      }
    }
    BigDecimal purchase = BigDecimal.valueOf(purchasePricePaise).movePointLeft(2);
    int totalUnits = paidQty + freeQty;
    if (totalUnits <= 0 || freeQty <= 0) {
      return purchase;
    }
    BigDecimal freeGoods = purchase.multiply(BigDecimal.valueOf(freeQty));
    BigDecimal deduction =
        freeGoods.divide(BigDecimal.valueOf(totalUnits), 2, RoundingMode.HALF_UP);
    return purchase.subtract(deduction);
  }

  public static BigDecimal marginPct(BigDecimal mrpRupees, BigDecimal landedRupees) {
    if (mrpRupees == null || mrpRupees.signum() <= 0 || landedRupees == null) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return mrpRupees
        .subtract(landedRupees)
        .multiply(BigDecimal.valueOf(100))
        .divide(mrpRupees, 1, RoundingMode.HALF_UP);
  }
}

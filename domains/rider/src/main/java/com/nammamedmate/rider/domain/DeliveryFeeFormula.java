package com.nammamedmate.rider.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * BR-001 / BR-002 / BR-005 / BR-008 delivery fee (rupees).
 *
 * <p>ponytail: story literal {@code (base + distance) - multiplier} is treated as typo; formula is
 * {@code round(base + distanceKm * perKm) * effectiveSurge} with effectiveSurge = configured surge
 * when active else 1.0. Free when {@code orderValue >= freeDeliveryThreshold}. Handling fee is
 * separate (never surged/waived).
 */
public final class DeliveryFeeFormula {

  public static final BigDecimal DEFAULT_HANDLING_FEE = new BigDecimal("5.00");
  public static final BigDecimal PLATFORM_CUT = new BigDecimal("0.70");
  public static final BigDecimal MIN_RIDER_PAYOUT = new BigDecimal("15.00");

  private DeliveryFeeFormula() {}

  public record Breakdown(
      BigDecimal baseFee,
      BigDecimal distanceCharge,
      BigDecimal subtotalBeforeSurge,
      BigDecimal surgeMultiplier,
      BigDecimal surgeCharge,
      BigDecimal deliveryFee,
      BigDecimal handlingFee,
      boolean freeDeliveryWaiver,
      BigDecimal totalCustomerPays,
      BigDecimal riderPayout) {}

  public static BigDecimal effectiveSurge(boolean surgeActive, BigDecimal surgeMultiplier) {
    if (!surgeActive) {
      return BigDecimal.ONE.setScale(2, RoundingMode.HALF_UP);
    }
    if (surgeMultiplier == null || surgeMultiplier.compareTo(BigDecimal.ONE) < 0) {
      return BigDecimal.ONE.setScale(2, RoundingMode.HALF_UP);
    }
    return surgeMultiplier.setScale(2, RoundingMode.HALF_UP);
  }

  public static BigDecimal estimateRupees(
      BigDecimal baseFee,
      BigDecimal perKmFee,
      double distanceKm,
      BigDecimal orderValue,
      BigDecimal freeDeliveryThreshold,
      boolean surgeActive,
      BigDecimal surgeMultiplier) {
    return breakdown(
            baseFee,
            perKmFee,
            distanceKm,
            orderValue,
            freeDeliveryThreshold,
            surgeActive,
            surgeMultiplier,
            DEFAULT_HANDLING_FEE)
        .deliveryFee();
  }

  public static Breakdown breakdown(
      BigDecimal baseFee,
      BigDecimal perKmFee,
      double distanceKm,
      BigDecimal orderValue,
      BigDecimal freeDeliveryThreshold,
      boolean surgeActive,
      BigDecimal surgeMultiplier,
      BigDecimal handlingFee) {
    BigDecimal handling =
        handlingFee == null ? DEFAULT_HANDLING_FEE : handlingFee.setScale(2, RoundingMode.HALF_UP);
    boolean free =
        orderValue != null
            && freeDeliveryThreshold != null
            && orderValue.compareTo(freeDeliveryThreshold) >= 0;
    BigDecimal base = money(baseFee);
    BigDecimal perKm = money(perKmFee);
    BigDecimal distanceCharge =
        perKm
            .multiply(BigDecimal.valueOf(Math.max(0, distanceKm)))
            .setScale(2, RoundingMode.HALF_UP);
    BigDecimal subtotal = base.add(distanceCharge).setScale(2, RoundingMode.HALF_UP);
    BigDecimal surge = effectiveSurge(surgeActive, surgeMultiplier);
    if (free) {
      BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
      return new Breakdown(
          base,
          distanceCharge,
          subtotal,
          surge,
          zero,
          zero,
          handling,
          true,
          handling,
          MIN_RIDER_PAYOUT);
    }
    BigDecimal afterSurge = roundRupee(subtotal.multiply(surge));
    BigDecimal surgeCharge = afterSurge.subtract(subtotal).max(BigDecimal.ZERO).setScale(2);
    BigDecimal rider = riderPayout(afterSurge);
    return new Breakdown(
        base,
        distanceCharge,
        subtotal,
        surge,
        surgeCharge,
        afterSurge,
        handling,
        false,
        afterSurge.add(handling).setScale(2, RoundingMode.HALF_UP),
        rider);
  }

  /** BR-005: max(delivery_fee - 0.70, Rs 15). Free delivery still pays Rs 15. */
  public static BigDecimal riderPayout(BigDecimal deliveryFee) {
    BigDecimal fee = deliveryFee == null ? BigDecimal.ZERO : deliveryFee;
    if (fee.compareTo(BigDecimal.ZERO) <= 0) {
      return MIN_RIDER_PAYOUT;
    }
    BigDecimal cut = fee.subtract(PLATFORM_CUT);
    return cut.max(MIN_RIDER_PAYOUT).setScale(2, RoundingMode.HALF_UP);
  }

  public static String riderPayoutNote(BigDecimal deliveryFee, BigDecimal payout) {
    BigDecimal fee = deliveryFee == null ? BigDecimal.ZERO : money(deliveryFee);
    BigDecimal pay = money(payout);
    if (fee.compareTo(BigDecimal.ZERO) <= 0) {
      return "Free delivery; rider receives Rs 15 minimum platform top-up.";
    }
    BigDecimal afterCut = fee.subtract(PLATFORM_CUT).setScale(2, RoundingMode.HALF_UP);
    if (afterCut.compareTo(MIN_RIDER_PAYOUT) >= 0) {
      return "delivery_fee - Rs 0.70 = Rs " + afterCut + "; above Rs 15 minimum.";
    }
    return "delivery_fee - Rs 0.70 = Rs "
        + afterCut
        + "; rider receives Rs 15 minimum (payout Rs "
        + pay
        + ").";
  }

  /** BR-001: round to nearest whole rupee, expose as .00 scale. */
  public static BigDecimal roundRupee(BigDecimal value) {
    if (value == null) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return value.setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
  }

  public static long toPaise(BigDecimal rupees) {
    if (rupees == null) {
      return 0L;
    }
    return rupees
        .multiply(BigDecimal.valueOf(100))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  public static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise, 2).setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal money(BigDecimal v) {
    return v == null
        ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
        : v.setScale(2, RoundingMode.HALF_UP);
  }
}

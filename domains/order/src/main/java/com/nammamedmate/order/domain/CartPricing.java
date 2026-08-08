package com.nammamedmate.order.domain;

import com.nammamedmate.kernel.error.AppException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Cart bill math in paise. Delivery threshold uses pre-coupon item_total. */
public final class CartPricing {

  public static final long HANDLING_FEE_PAISE = 500L;
  public static final long DELIVERY_FEE_PAISE = 2500L;
  public static final long FREE_DELIVERY_THRESHOLD_PAISE = 19_900L;
  public static final long FLAT50_MIN_ITEM_TOTAL_PAISE = 39_900L;
  public static final long FLAT50_OFF_PAISE = 5_000L;

  public enum CouponType {
    PERCENT,
    FLAT,
    FREE_DELIVERY
  }

  public record Bill(
      long itemTotalPaise,
      long couponDiscountPaise,
      long subtotalAfterDiscountPaise,
      long deliveryFeePaise,
      long handlingFeePaise,
      long walletAppliedPaise,
      long totalPayablePaise) {}

  public record CouponResult(String code, CouponType type, long discountPaise, String message) {}

  private CartPricing() {}

  public static Bill compute(long itemTotalPaise, String couponCode, long walletBalancePaise) {
    long safeItem = Math.max(itemTotalPaise, 0L);
    boolean empty = safeItem <= 0;
    String code = normalize(couponCode);
    long delivery = deliveryFeePaise(safeItem, code, empty);
    long handling = empty ? 0L : HANDLING_FEE_PAISE;
    return compute(safeItem, code, walletBalancePaise, delivery, handling);
  }

  /** Bill with zone-priced delivery/handling (STORY-006). Coupon discount still from item total. */
  public static Bill compute(
      long itemTotalPaise,
      String couponCode,
      long walletBalancePaise,
      long deliveryFeePaise,
      long handlingFeePaise) {
    long safeItem = Math.max(itemTotalPaise, 0L);
    boolean empty = safeItem <= 0;
    String code = normalize(couponCode);
    long discount = empty ? 0L : couponDiscountPaise(code, safeItem);
    long subtotal = safeItem - discount;
    long handling = empty ? 0L : Math.max(handlingFeePaise, 0L);
    long delivery = empty ? 0L : Math.max(deliveryFeePaise, 0L);
    if ("FREEDEL".equals(code)) {
      delivery = 0L;
    }
    long beforeWallet = subtotal + delivery + handling;
    long wallet = Math.min(Math.max(walletBalancePaise, 0L), Math.max(beforeWallet, 0L));
    long payable = beforeWallet - wallet;
    return new Bill(safeItem, discount, subtotal, delivery, handling, wallet, payable);
  }

  public static CouponResult applyCoupon(String couponCode, long itemTotalPaise) {
    String code = normalize(couponCode);
    if (code == null) {
      throw new AppException("INVALID_COUPON", "Coupon code not found or expired", 422);
    }
    return switch (code) {
      case "NAMMA25" ->
          new CouponResult(
              code,
              CouponType.PERCENT,
              couponDiscountPaise(code, itemTotalPaise),
              "25% discount applied");
      case "FLAT50" -> {
        if (itemTotalPaise < FLAT50_MIN_ITEM_TOTAL_PAISE) {
          throw new AppException(
              "COUPON_MIN_NOT_MET", "FLAT50 requires minimum cart of Rs 399", 422);
        }
        yield new CouponResult(
            code,
            CouponType.FLAT,
            couponDiscountPaise(code, itemTotalPaise),
            "Rs 50 discount applied");
      }
      case "FREEDEL" ->
          new CouponResult(code, CouponType.FREE_DELIVERY, 0L, "Free delivery applied");
      default -> throw new AppException("INVALID_COUPON", "Coupon code not found or expired", 422);
    };
  }

  public static long couponDiscountPaise(String couponCode, long itemTotalPaise) {
    String code = normalize(couponCode);
    if (code == null || itemTotalPaise <= 0) {
      return 0L;
    }
    long discount =
        switch (code) {
          case "NAMMA25" -> (itemTotalPaise * 25L) / 100L;
          case "FLAT50" -> FLAT50_OFF_PAISE;
          case "FREEDEL" -> 0L;
          default -> 0L;
        };
    return Math.min(discount, itemTotalPaise);
  }

  public static long deliveryFeePaise(long itemTotalPaise, String couponCode, boolean empty) {
    if (empty) {
      return 0L;
    }
    if ("FREEDEL".equals(normalize(couponCode))) {
      return 0L;
    }
    return itemTotalPaise < FREE_DELIVERY_THRESHOLD_PAISE ? DELIVERY_FEE_PAISE : 0L;
  }

  public static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise, 2).setScale(2, RoundingMode.HALF_UP);
  }

  public static String normalize(String couponCode) {
    if (couponCode == null || couponCode.isBlank()) {
      return null;
    }
    return couponCode.trim().toUpperCase(Locale.ROOT);
  }
}

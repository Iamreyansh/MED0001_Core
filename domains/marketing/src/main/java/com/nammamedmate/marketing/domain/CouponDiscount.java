package com.nammamedmate.marketing.domain;

/** Discount math in paise. PERCENTAGE = MIN(cart * pct/100, max_cap). */
public final class CouponDiscount {

  private CouponDiscount() {}

  public static long discountPaise(Coupon coupon, long cartTotalPaise) {
    if (cartTotalPaise <= 0 || coupon == null) {
      return 0L;
    }
    long raw =
        switch (coupon.type()) {
          case PERCENTAGE -> {
            int pct = coupon.percentValue() == null ? 0 : coupon.percentValue();
            yield (cartTotalPaise * pct) / 100L;
          }
          case FLAT_RS -> {
            Long v = coupon.valuePaise();
            yield v == null ? 0L : v;
          }
          case FREE_DELIVERY -> 0L;
        };
    if (coupon.type() == CouponType.PERCENTAGE && coupon.maxDiscountCapPaise() != null) {
      raw = Math.min(raw, coupon.maxDiscountCapPaise());
    }
    return Math.min(Math.max(raw, 0L), cartTotalPaise);
  }
}

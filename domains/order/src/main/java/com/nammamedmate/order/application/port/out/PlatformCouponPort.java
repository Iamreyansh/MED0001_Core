package com.nammamedmate.order.application.port.out;

import com.nammamedmate.order.domain.CartPricing.CouponType;

/** Cross-domain coupon quote (bridged from marketing in apps/api). */
public interface PlatformCouponPort {

  record Quote(
      String code, CouponType type, long discountPaise, boolean freeDelivery, String message) {}

  Quote apply(String couponCode, long itemTotalPaise);
}

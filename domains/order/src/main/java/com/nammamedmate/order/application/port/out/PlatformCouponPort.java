package com.nammamedmate.order.application.port.out;

import com.nammamedmate.order.domain.CartPricing.CouponType;

/** Cross-domain coupon quote (bridged from marketing in apps/api). */
public interface PlatformCouponPort {

  record Quote(
      String code, CouponType type, long discountPaise, boolean freeDelivery, String message) {}

  Quote apply(String couponCode, long itemTotalPaise);

  default void record(
      String couponCode,
      java.util.UUID orderId,
      java.util.UUID customerId,
      long discountPaise,
      long orderTotalPaise) {}
}

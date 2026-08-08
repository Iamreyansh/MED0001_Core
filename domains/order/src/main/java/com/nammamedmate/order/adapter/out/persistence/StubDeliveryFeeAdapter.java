package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.order.application.port.out.DeliveryFeePort;
import com.nammamedmate.order.domain.CartPricing;
import java.util.Optional;
import java.util.UUID;

/** Flat CartPricing fees until apps/api DeliveryFeePort bridge is active. */
public class StubDeliveryFeeAdapter implements DeliveryFeePort {

  @Override
  public Optional<FeeQuote> quote(
      UUID pharmacyId,
      Double deliveryLat,
      Double deliveryLng,
      long itemTotalPaise,
      boolean freeDeliveryCoupon) {
    if (pharmacyId == null || deliveryLat == null || deliveryLng == null) {
      return Optional.empty();
    }
    boolean empty = itemTotalPaise <= 0;
    String coupon = freeDeliveryCoupon ? "FREEDEL" : null;
    long delivery = CartPricing.deliveryFeePaise(itemTotalPaise, coupon, empty);
    long handling = empty ? 0L : CartPricing.HANDLING_FEE_PAISE;
    return Optional.of(new FeeQuote(delivery, handling, null, 0.0, delivery == 0L && !empty, null));
  }

  @Override
  public void lockSnapshot(UUID orderId, FeeQuote quote) {
    // no-op stub
  }
}

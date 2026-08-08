package com.nammamedmate.order.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Zone-based delivery fee (EPIC-011/STORY-006). Composition root supplies a {@code @Primary} bridge
 * to rider pricing; order module ships a flat-fee stub.
 */
public interface DeliveryFeePort {

  record FeeQuote(
      long deliveryFeePaise,
      long handlingFeePaise,
      UUID zoneId,
      double distanceKm,
      boolean freeDelivery,
      Object lockToken) {}

  /**
   * Quote for cart/placement. Empty → caller uses {@link com.nammamedmate.order.domain.CartPricing}
   * flat fee.
   */
  Optional<FeeQuote> quote(
      UUID pharmacyId,
      Double deliveryLat,
      Double deliveryLng,
      long itemTotalPaise,
      boolean freeDeliveryCoupon);

  /** Persist locked snapshot at order placement (no-op when quote has no zone). */
  void lockSnapshot(UUID orderId, FeeQuote quote);
}

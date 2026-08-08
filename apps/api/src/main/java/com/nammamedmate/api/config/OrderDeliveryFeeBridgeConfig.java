package com.nammamedmate.api.config;

import com.nammamedmate.order.application.port.out.DeliveryFeePort;
import com.nammamedmate.rider.application.DeliveryPricingService;
import com.nammamedmate.rider.application.DeliveryPricingService.LockedQuote;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Composition-root bridge: order {@link DeliveryFeePort} → rider {@link DeliveryPricingService}
 * (EPIC-011/STORY-006). Locks {@code delivery_fee_snapshots} at placement.
 */
@Configuration
public class OrderDeliveryFeeBridgeConfig {

  @Bean
  @Primary
  DeliveryFeePort riderDeliveryFeePort(DeliveryPricingService pricing) {
    return new DeliveryFeePort() {
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
        return pricing
            .quoteForDelivery(
                pharmacyId, deliveryLat, deliveryLng, itemTotalPaise, freeDeliveryCoupon)
            .map(
                q ->
                    new FeeQuote(
                        q.deliveryFeePaise(),
                        q.handlingFeePaise(),
                        q.zoneId(),
                        q.distanceKm().doubleValue(),
                        q.freeDelivery(),
                        q));
      }

      @Override
      public void lockSnapshot(UUID orderId, FeeQuote quote) {
        if (quote == null || !(quote.lockToken() instanceof LockedQuote locked)) {
          return;
        }
        pricing.lockSnapshot(orderId, locked);
      }
    };
  }
}

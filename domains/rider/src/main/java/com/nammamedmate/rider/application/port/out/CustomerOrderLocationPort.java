package com.nammamedmate.rider.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Cross-domain order lookup for customer rider-location (JDBC bridge; no domain dep). */
public interface CustomerOrderLocationPort {

  record OrderLocationContext(
      UUID orderId,
      UUID customerId,
      String status,
      UUID riderId,
      String riderName,
      Double deliveryLat,
      Double deliveryLng) {}

  Optional<OrderLocationContext> findById(UUID orderId);
}

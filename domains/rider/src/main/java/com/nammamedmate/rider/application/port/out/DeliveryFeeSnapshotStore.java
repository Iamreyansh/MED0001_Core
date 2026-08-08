package com.nammamedmate.rider.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryFeeSnapshotStore {

  record Snapshot(
      UUID orderId,
      UUID zoneId,
      BigDecimal distanceKm,
      BigDecimal baseFee,
      BigDecimal distanceCharge,
      BigDecimal surgeMultiplier,
      BigDecimal deliveryFee,
      BigDecimal handlingFee,
      boolean freeDelivery,
      BigDecimal riderPayout,
      Instant createdAt) {}

  void insert(Snapshot snapshot);

  Optional<Snapshot> findByOrderId(UUID orderId);
}

package com.nammamedmate.rider.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Cross-domain (orders) lookup — wired in apps/api; stub in RiderConfig. */
public interface ActiveDeliveryPort {

  record ActiveOrder(
      UUID orderId, String orderStatus, String customerAddressShort, Integer etaMinutes) {}

  Optional<ActiveOrder> findActiveByRider(UUID riderId);

  /** Live (non-terminal) orders whose pharmacy is in the zone. */
  int countLiveOrdersInZone(UUID zoneId);

  /** Mark order for ops monitoring when rider goes offline mid-delivery (best-effort). */
  void flagForMonitoring(UUID orderId, String reason);
}

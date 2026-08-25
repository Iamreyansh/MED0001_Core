package com.nammamedmate.rider.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** Canonical order DELIVERED transition (invoice, ledger, order.delivered). Wired in apps/api. */
public interface OrderDeliveryConfirmPort {

  void confirmDelivered(UUID orderId, UUID riderId, Instant now);
}

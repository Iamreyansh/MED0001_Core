package com.nammamedmate.payment.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Cross-domain order snapshot for payment initiate / ownership checks (bridged in apps/api). */
public interface OrderLookupPort {

  record OrderSnapshot(
      UUID orderId,
      UUID customerId,
      String paymentMethod,
      long totalPayablePaise,
      long walletAppliedPaise,
      String status) {}

  Optional<OrderSnapshot> findById(UUID orderId);
}

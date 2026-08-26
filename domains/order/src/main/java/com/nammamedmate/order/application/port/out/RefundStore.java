package com.nammamedmate.order.application.port.out;

import com.nammamedmate.order.domain.Refund;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundStore {

  void insert(Refund refund);

  void update(Refund refund);

  Optional<Refund> findById(UUID id);

  Optional<Refund> findByIdempotencyKey(String idempotencyKey);

  Optional<Refund> findByGatewayRefundId(String gatewayRefundId);

  List<Refund> listByOrderId(UUID orderId);

  long sumSuccessfulPaise(UUID orderId);
}

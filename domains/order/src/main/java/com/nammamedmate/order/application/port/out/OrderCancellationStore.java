package com.nammamedmate.order.application.port.out;

import com.nammamedmate.order.domain.OrderCancellation;
import java.util.Optional;
import java.util.UUID;

public interface OrderCancellationStore {

  void insert(OrderCancellation cancellation);

  Optional<OrderCancellation> findByOrderId(UUID orderId);
}

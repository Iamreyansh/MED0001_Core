package com.nammamedmate.order.application.port.out;

import com.nammamedmate.order.domain.OrderDispute;
import java.util.Optional;
import java.util.UUID;

public interface OrderDisputeStore {

  OrderDispute insert(OrderDispute dispute);

  Optional<OrderDispute> findOpenByOrderId(UUID orderId);
}

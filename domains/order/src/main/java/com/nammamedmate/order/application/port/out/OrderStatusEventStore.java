package com.nammamedmate.order.application.port.out;

import com.nammamedmate.order.domain.OrderStatusEvent;
import java.util.List;
import java.util.UUID;

public interface OrderStatusEventStore {

  void append(OrderStatusEvent event);

  List<OrderStatusEvent> listByOrderId(UUID orderId);
}

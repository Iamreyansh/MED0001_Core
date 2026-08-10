package com.nammamedmate.support.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Order eligibility + context for disputes (bridged from orders in apps/api). */
public interface OrderContextPort {

  record OrderItem(String name, int qty, long pricePaise) {}

  record OrderContext(
      UUID orderId,
      UUID customerId,
      String status,
      long totalPayablePaise,
      List<OrderItem> items,
      String pharmacyName,
      String riderName,
      String deliveryTrackingUrl) {

    public OrderContext {
      items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean delivered() {
      return "DELIVERED".equalsIgnoreCase(status);
    }
  }

  Optional<OrderContext> find(UUID orderId);
}

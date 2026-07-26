package com.nammamedmate.customer.application.port.out;

import java.util.UUID;

/** Whether the customer has ever placed an order. Stubbed false until EPIC-010 wires orders. */
@FunctionalInterface
public interface CustomerOrderHistoryPort {

  boolean hasPlacedAnyOrder(UUID customerId);
}

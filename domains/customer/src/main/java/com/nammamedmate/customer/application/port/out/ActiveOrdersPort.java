package com.nammamedmate.customer.application.port.out;

import java.util.UUID;

/** Cross-domain query for open orders; implemented by order module when available. */
@FunctionalInterface
public interface ActiveOrdersPort {

  boolean hasActiveOrders(UUID customerId);
}

package com.nammamedmate.support.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface CustomerLookupPort {

  record CustomerContext(UUID customerId, String customerName, long totalOrders, long ltvRs) {}

  Optional<CustomerContext> find(UUID customerId);

  Optional<String> displayName(UUID customerId);
}

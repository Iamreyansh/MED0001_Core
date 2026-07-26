package com.nammamedmate.customer.application.port.out;

import java.util.UUID;

/**
 * Cross-domain check: address used by an order in PENDING, CONFIRMED, PACKED, or OUT_FOR_DELIVERY.
 */
@FunctionalInterface
public interface AddressInActiveOrderPort {

  boolean isAddressInActiveOrder(UUID addressId);
}

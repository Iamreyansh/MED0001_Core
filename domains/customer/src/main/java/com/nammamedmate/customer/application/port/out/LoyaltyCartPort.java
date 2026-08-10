package com.nammamedmate.customer.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Cart item-total lookup for loyalty redemption cap (bridged in apps/api). */
public interface LoyaltyCartPort {

  /**
   * @return empty when cart missing or not owned by customer
   */
  Optional<Long> findCartItemTotalPaise(UUID customerId, UUID cartId);
}

package com.nammamedmate.order.application.port.out;

import com.nammamedmate.order.domain.Cart;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CartStore {

  Optional<Cart> findActiveByCustomer(UUID customerId);

  Optional<Cart> findById(UUID cartId);

  Cart insert(Cart cart);

  Cart update(Cart cart);

  /** ACTIVE carts with updated_at older than cutoff → ABANDONED. Returns rows updated. */
  int abandonStale(Instant cutoff);
}

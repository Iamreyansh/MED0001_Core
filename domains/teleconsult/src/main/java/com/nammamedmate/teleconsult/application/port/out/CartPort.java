package com.nammamedmate.teleconsult.application.port.out;

import java.util.UUID;

/** Validates cart ownership/active status without a domain→domain compile dep. */
public interface CartPort {

  /** True when cart exists, belongs to customer, and status is ACTIVE. */
  boolean isActiveCartOwnedBy(UUID cartId, UUID customerId);
}

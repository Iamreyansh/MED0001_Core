package com.nammamedmate.teleconsult.application.port.out;

import java.util.UUID;

/** Composition-root bridge to attach e-Rx to cart (mirrors prescription OrderLinkPort). */
public interface CartLinkPort {

  /**
   * Attach prescription to an ACTIVE cart owned by customer.
   *
   * @throws com.nammamedmate.kernel.error.AppException CART_NOT_FOUND / CART_PRESCRIPTION_MISMATCH
   */
  void attachPrescription(UUID customerId, UUID cartId, UUID prescriptionId);
}

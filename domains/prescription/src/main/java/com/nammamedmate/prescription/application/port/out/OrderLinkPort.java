package com.nammamedmate.prescription.application.port.out;

import java.util.UUID;

/** Composition-root bridge to cart attach (no domain→domain compile dep). */
public interface OrderLinkPort {

  /**
   * Attach prescription to an ACTIVE cart owned by customer.
   *
   * @throws com.nammamedmate.kernel.error.AppException CART_NOT_FOUND / CART_PRESCRIPTION_MISMATCH
   */
  void attachToCart(UUID customerId, UUID cartId, UUID prescriptionId);
}

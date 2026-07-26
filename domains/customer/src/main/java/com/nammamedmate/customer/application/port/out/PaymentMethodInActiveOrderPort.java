package com.nammamedmate.customer.application.port.out;

import java.util.UUID;

/** True when the payment method is the source for an in-flight order (EPIC-010). */
@FunctionalInterface
public interface PaymentMethodInActiveOrderPort {

  boolean isPaymentMethodInActiveOrder(UUID paymentMethodId);
}

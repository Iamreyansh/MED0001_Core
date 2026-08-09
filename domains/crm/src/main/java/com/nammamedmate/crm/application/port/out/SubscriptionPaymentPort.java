package com.nammamedmate.crm.application.port.out;

import java.util.UUID;

/** Charge SaaS subscription amounts. Stub succeeds by default until payment gateway lands. */
public interface SubscriptionPaymentPort {

  /**
   * @param idempotencyKey payment-provider idempotency key (required for real charges)
   * @return payment reference id (invoice rows are created via {@link InvoiceIssuingPort})
   * @throws com.nammamedmate.kernel.error.AppException with code PAYMENT_FAILED on failure
   */
  UUID charge(UUID accountId, long amountPaise, String description, String idempotencyKey);
}

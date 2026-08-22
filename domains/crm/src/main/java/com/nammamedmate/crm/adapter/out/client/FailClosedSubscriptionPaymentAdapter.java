package com.nammamedmate.crm.adapter.out.client;

import com.nammamedmate.crm.application.port.out.SubscriptionPaymentPort;
import com.nammamedmate.kernel.error.AppException;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Paid CRM subscribe stays disabled until a real payment adapter exists. */
@Component
@Profile({"prod", "staging"})
public class FailClosedSubscriptionPaymentAdapter implements SubscriptionPaymentPort {

  @Override
  public UUID charge(UUID accountId, long amountPaise, String description, String idempotencyKey) {
    if (amountPaise <= 0) {
      throw new AppException("PAYMENT_FAILED", "Payment initiation failed", 402);
    }
    throw new AppException(
        "SUBSCRIPTION_PAYMENTS_DISABLED", "Paid CRM subscriptions are not enabled", 503);
  }
}

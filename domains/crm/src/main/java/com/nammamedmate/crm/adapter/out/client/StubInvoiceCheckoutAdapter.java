package com.nammamedmate.crm.adapter.out.client;

import com.nammamedmate.crm.application.port.out.InvoiceCheckoutPort;
import com.nammamedmate.kernel.id.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class StubInvoiceCheckoutAdapter implements InvoiceCheckoutPort {

  private final Clock clock;

  public StubInvoiceCheckoutAdapter(Clock clock) {
    this.clock = clock;
  }

  @Override
  public CheckoutSession createCheckout(UUID invoiceId, long amountPaise, String paymentMethod) {
    String payId = Ids.newId().toString().replace("-", "");
    Instant expires = clock.instant().plusSeconds(30 * 60);
    return new CheckoutSession("https://razorpay.com/checkout/pay_" + payId, expires, "Razorpay");
  }
}

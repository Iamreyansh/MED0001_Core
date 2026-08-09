package com.nammamedmate.crm.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.crm.application.port.out.InvoiceCheckoutPort;
import com.nammamedmate.kernel.id.Ids;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class StubInvoiceCheckoutAdapterTest {

  @Test
  void createsCheckoutUrl() {
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    StubInvoiceCheckoutAdapter stub =
        new StubInvoiceCheckoutAdapter(Clock.fixed(now, ZoneOffset.UTC));
    InvoiceCheckoutPort.CheckoutSession session = stub.createCheckout(Ids.newId(), 1000, "UPI");
    assertThat(session.gateway()).isEqualTo("Razorpay");
    assertThat(session.checkoutUrl()).startsWith("https://razorpay.com/checkout/pay_");
    assertThat(session.expiresAt()).isEqualTo(now.plusSeconds(1800));
  }
}

package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.InvoiceCheckoutPort;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.payment.application.port.out.RazorpayGatewayPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CrmPaymentBridgeConfigTest {

  @Test
  void checkoutUsesPaymentGatewayOrder() {
    RazorpayGatewayPort razorpay = mock(RazorpayGatewayPort.class);
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    var invoiceId = Ids.newId();
    when(razorpay.createOrder(invoiceId, 1000L))
        .thenReturn(new RazorpayGatewayPort.CreateOrderResult("order_abc", 1000L, "rzp_test"));

    InvoiceCheckoutPort port =
        new CrmPaymentBridgeConfig().paymentInvoiceCheckoutPort(razorpay, clock);
    InvoiceCheckoutPort.CheckoutSession session = port.createCheckout(invoiceId, 1000L, "UPI");
    assertThat(session.checkoutUrl()).contains("order_abc");
    assertThat(session.gateway()).isEqualTo("Razorpay");
    assertThat(session.expiresAt()).isEqualTo(now.plusSeconds(1800));
  }
}

package com.nammamedmate.api.config;

import com.nammamedmate.crm.application.port.out.InvoiceCheckoutPort;
import com.nammamedmate.payment.application.port.out.RazorpayGatewayPort;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** SaaS invoice checkout via the payment-domain Razorpay gateway. */
@Configuration
public class CrmPaymentBridgeConfig {

  @Bean
  @Primary
  InvoiceCheckoutPort paymentInvoiceCheckoutPort(RazorpayGatewayPort razorpay, Clock clock) {
    return (invoiceId, amountPaise, paymentMethod) -> {
      RazorpayGatewayPort.CreateOrderResult created = razorpay.createOrder(invoiceId, amountPaise);
      return new InvoiceCheckoutPort.CheckoutSession(
          "https://checkout.razorpay.com/v1/checkout.js?order_id=" + created.razorpayOrderId(),
          clock.instant().plusSeconds(30 * 60),
          "Razorpay");
    };
  }
}

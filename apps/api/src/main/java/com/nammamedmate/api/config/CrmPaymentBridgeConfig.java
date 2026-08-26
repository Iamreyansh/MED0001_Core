package com.nammamedmate.api.config;

import com.nammamedmate.crm.application.port.out.InvoiceCheckoutPort;
import com.nammamedmate.payment.application.port.out.CashfreeGatewayPort;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** SaaS invoice checkout via the payment-domain Cashfree gateway. */
@Configuration
public class CrmPaymentBridgeConfig {

  @Bean
  @Primary
  InvoiceCheckoutPort paymentInvoiceCheckoutPort(CashfreeGatewayPort cashfree, Clock clock) {
    return (invoiceId, amountPaise, paymentMethod) -> {
      CashfreeGatewayPort.CreateOrderResult created = cashfree.createOrder(invoiceId, amountPaise);
      return new InvoiceCheckoutPort.CheckoutSession(
          "https://sdk.cashfree.com/js/v3/cashfree.js?payment_session_id="
              + (created.paymentSessionId() == null || created.paymentSessionId().isBlank()
                  ? created.gatewayOrderId()
                  : created.paymentSessionId()),
          clock.instant().plusSeconds(30 * 60),
          "Cashfree");
    };
  }
}

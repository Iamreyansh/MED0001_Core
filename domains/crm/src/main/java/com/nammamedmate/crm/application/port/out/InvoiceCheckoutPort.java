package com.nammamedmate.crm.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** Razorpay checkout initiation for SaaS invoice payment. */
public interface InvoiceCheckoutPort {

  record CheckoutSession(String checkoutUrl, Instant expiresAt, String gateway) {}

  CheckoutSession createCheckout(UUID invoiceId, long amountPaise, String paymentMethod);
}

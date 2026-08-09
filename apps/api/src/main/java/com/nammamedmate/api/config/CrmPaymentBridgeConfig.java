package com.nammamedmate.api.config;

import org.springframework.context.annotation.Configuration;

/**
 * Composition-root placeholder for SaaS invoice Razorpay wiring.
 *
 * <p>Checkout currently uses {@code StubInvoiceCheckoutAdapter} in {@code domains/crm}. When live
 * Razorpay keys are configured, replace that bean here with a payment-domain bridge (no
 * domain→domain compile dependency).
 */
@Configuration
public class CrmPaymentBridgeConfig {}

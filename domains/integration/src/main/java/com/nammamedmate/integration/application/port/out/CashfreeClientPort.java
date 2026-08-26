package com.nammamedmate.integration.application.port.out;

import java.util.Map;

public interface CashfreeClientPort {

  CreateOrderResult createOrder(
      long amountPaise, String currency, String receipt, Map<String, String> notes);

  CaptureResult capturePayment(String gatewayPaymentId, long amountPaise);

  UpiVerifyResult verifyUpi(String vpa);

  boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody);

  default boolean verifyWebhookSignature(
      String signatureHeader, String timestampHeader, byte[] rawBody) {
    return verifyWebhookSignature(signatureHeader, rawBody);
  }

  /** TEST or LIVE (config flag; stub always reports TEST). */
  String mode();

  record CreateOrderResult(
      String gatewayOrderId,
      String paymentSessionId,
      long amountPaise,
      String currency,
      String receipt,
      String status) {
    public CreateOrderResult(
        String gatewayOrderId, long amountPaise, String currency, String receipt, String status) {
      this(gatewayOrderId, "", amountPaise, currency, receipt, status);
    }
  }

  record CaptureResult(String gatewayPaymentId, String status) {}

  record UpiVerifyResult(String vpa, boolean valid, String name) {}
}

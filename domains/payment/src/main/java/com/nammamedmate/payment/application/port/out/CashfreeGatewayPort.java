package com.nammamedmate.payment.application.port.out;

import java.util.UUID;

/** Cashfree PG orders + webhook HMAC (live when keys configured; else stub). */
public interface CashfreeGatewayPort {

  record CreateOrderResult(
      String gatewayOrderId, String paymentSessionId, long amountPaise, String appId) {
    public CreateOrderResult(String gatewayOrderId, long amountPaise, String appId) {
      this(gatewayOrderId, "", amountPaise, appId);
    }

    /**
     * @deprecated use {@link #appId()}
     */
    public String keyId() {
      return appId();
    }
  }

  CreateOrderResult createOrder(UUID medmateOrderId, long amountPaise);

  boolean verifyPaymentSignature(String gatewayOrderId, String paymentId, String signature);

  /** HMAC hex of {@code gatewayOrderId|paymentId} — tests / stub clients. */
  String signPayment(String gatewayOrderId, String paymentId);

  boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody);

  default boolean verifyWebhookSignature(
      String signatureHeader, String timestampHeader, byte[] rawBody) {
    return verifyWebhookSignature(signatureHeader, rawBody);
  }

  String keyId();

  record RefundResult(String gatewayRefundId, long amountPaise) {}

  /** Create a Cashfree refund against a captured payment. */
  RefundResult refund(String gatewayPaymentId, long amountPaise);
}

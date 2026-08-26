package com.nammamedmate.order.application.port.out;

import java.util.Map;
import java.util.UUID;

/** Cashfree Orders + payment signature verify (stub until live keys). */
public interface CashfreePaymentPort {

  record CreateOrderResult(String gatewayOrderId, long amountPaise) {}

  record RefundResult(String gatewayRefundId, long amountPaise) {}

  CreateOrderResult createOrder(UUID orderId, long amountPaise);

  boolean verifyPaymentSignature(String gatewayOrderId, String paymentId, String signature);

  /** HMAC hex of {@code gatewayOrderId|paymentId} — for tests / stub clients. */
  String signPayment(String gatewayOrderId, String paymentId);

  boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody);

  /** Server-side refund against a captured payment. */
  RefundResult refund(String gatewayPaymentId, long amountPaise);

  /** Canonical webhook handling — payment domain owns capture/ledger. */
  default Map<String, Object> handleWebhook(String signatureHeader, byte[] rawBody) {
    return Map.of("processed", false);
  }
}

package com.nammamedmate.order.application.port.out;

import java.util.Map;
import java.util.UUID;

/** Razorpay Orders + payment signature verify (stub until live keys). */
public interface RazorpayPaymentPort {

  record CreateOrderResult(String razorpayOrderId, long amountPaise) {}

  record RefundResult(String razorpayRefundId, long amountPaise) {}

  CreateOrderResult createOrder(UUID orderId, long amountPaise);

  boolean verifyPaymentSignature(String razorpayOrderId, String paymentId, String signature);

  /** HMAC hex of {@code razorpayOrderId|paymentId} — for tests / stub clients. */
  String signPayment(String razorpayOrderId, String paymentId);

  boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody);

  /** Server-side refund against a captured payment. */
  RefundResult refund(String razorpayPaymentId, long amountPaise);

  /** Canonical webhook handling — payment domain owns capture/ledger. */
  default Map<String, Object> handleWebhook(String signatureHeader, byte[] rawBody) {
    return Map.of("processed", false);
  }
}

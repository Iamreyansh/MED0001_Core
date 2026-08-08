package com.nammamedmate.order.application.port.out;

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
}

package com.nammamedmate.payment.application.port.out;

import java.util.UUID;

/** Razorpay Orders + HMAC verify (live when keys configured; else stub). */
public interface RazorpayGatewayPort {

  record CreateOrderResult(String razorpayOrderId, long amountPaise, String keyId) {}

  CreateOrderResult createOrder(UUID medmateOrderId, long amountPaise);

  boolean verifyPaymentSignature(String razorpayOrderId, String paymentId, String signature);

  /** HMAC hex of {@code razorpayOrderId|paymentId} — tests / stub clients. */
  String signPayment(String razorpayOrderId, String paymentId);

  boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody);

  String keyId();

  record RefundResult(String razorpayRefundId, long amountPaise) {}

  /** Create a Razorpay refund against a captured payment. */
  RefundResult refund(String razorpayPaymentId, long amountPaise);
}

package com.nammamedmate.integration.application.port.out;

import java.util.Map;

public interface RazorpayClientPort {

  CreateOrderResult createOrder(
      long amountPaise, String currency, String receipt, Map<String, String> notes);

  CaptureResult capturePayment(String razorpayPaymentId, long amountPaise);

  UpiVerifyResult verifyUpi(String vpa);

  boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody);

  /** TEST or LIVE (config flag; stub always reports TEST). */
  String mode();

  record CreateOrderResult(
      String razorpayOrderId, long amountPaise, String currency, String receipt, String status) {}

  record CaptureResult(String razorpayPaymentId, String status) {}

  record UpiVerifyResult(String vpa, boolean valid, String name) {}
}

package com.nammamedmate.payment.adapter.out.client;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.RazorpayGatewayPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Deterministic Razorpay stub: order ids + HMAC with configurable test secrets (mirrors order /
 * pharmacy stub style).
 */
public final class StubRazorpayGatewayClient implements RazorpayGatewayPort {

  public static final String DEFAULT_KEY_ID = "rzp_test_stub";
  public static final String DEFAULT_KEY_SECRET = "test_razorpay_secret";
  public static final String DEFAULT_WEBHOOK_SECRET = "test_razorpay_webhook_secret";

  private final String keyId;
  private final String keySecret;
  private final String webhookSecret;
  private final boolean failCreate;

  public StubRazorpayGatewayClient() {
    this(DEFAULT_KEY_ID, DEFAULT_KEY_SECRET, DEFAULT_WEBHOOK_SECRET, false);
  }

  public StubRazorpayGatewayClient(String keyId, String keySecret, String webhookSecret) {
    this(keyId, keySecret, webhookSecret, false);
  }

  public StubRazorpayGatewayClient(
      String keyId, String keySecret, String webhookSecret, boolean failCreate) {
    this.keyId = keyId == null || keyId.isBlank() ? DEFAULT_KEY_ID : keyId;
    this.keySecret = keySecret == null || keySecret.isBlank() ? DEFAULT_KEY_SECRET : keySecret;
    this.webhookSecret =
        webhookSecret == null || webhookSecret.isBlank() ? DEFAULT_WEBHOOK_SECRET : webhookSecret;
    this.failCreate = failCreate;
  }

  @Override
  public CreateOrderResult createOrder(UUID orderId, long amountPaise) {
    if (failCreate) {
      throw new AppException("RAZORPAY_ERROR", "Razorpay order creation failed", 502);
    }
    if (amountPaise <= 0) {
      throw new AppException("VALIDATION_ERROR", "amount must be positive", 400);
    }
    String shortId = orderId.toString().replace("-", "").substring(0, 12);
    return new CreateOrderResult("order_stub_" + shortId, amountPaise, keyId);
  }

  @Override
  public boolean verifyPaymentSignature(
      String razorpayOrderId, String paymentId, String signature) {
    if (razorpayOrderId == null) {
      return false;
    }
    if (paymentId == null) {
      return false;
    }
    if (signature == null) {
      return false;
    }
    if (signature.isBlank()) {
      return false;
    }
    String expected = signPayment(razorpayOrderId, paymentId);
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String signPayment(String razorpayOrderId, String paymentId) {
    return hmacHex(keySecret, razorpayOrderId + "|" + paymentId);
  }

  @Override
  public boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody) {
    if (signatureHeader == null) {
      return false;
    }
    if (rawBody == null) {
      return false;
    }
    String expected = hmacHex(webhookSecret, new String(rawBody, StandardCharsets.UTF_8));
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        signatureHeader.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String keyId() {
    return keyId;
  }

  @Override
  public RefundResult refund(String razorpayPaymentId, long amountPaise) {
    if (failCreate) {
      throw new AppException("RAZORPAY_REFUND_FAILED", "Razorpay refund failed", 502);
    }
    if (razorpayPaymentId == null || razorpayPaymentId.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "razorpay payment id required", 400);
    }
    if (amountPaise <= 0) {
      throw new AppException("VALIDATION_ERROR", "amount must be positive", 400);
    }
    String shortId =
        razorpayPaymentId.length() <= 12
            ? razorpayPaymentId
            : razorpayPaymentId.substring(razorpayPaymentId.length() - 12);
    return new RefundResult("rfnd_stub_" + shortId + "_" + amountPaise, amountPaise);
  }

  public static String hmacHex(String secret, String payload) {
    return hmacHex(secret, payload, "HmacSHA256");
  }

  /** Visible for tests — pass a bogus algorithm to hit the failure path. */
  static String hmacHex(String secret, String payload, String algorithm) {
    try {
      Mac mac = Mac.getInstance(algorithm);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
      return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("HMAC failed", e);
    }
  }
}

package com.nammamedmate.order.adapter.out.client;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.order.application.port.out.CashfreePaymentPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Deterministic Cashfree stub: order ids + HMAC with configurable test secret (mirrors pharmacy
 * CashfreePayout stub style).
 */
public final class StubCashfreePaymentPort implements CashfreePaymentPort {

  public static final String DEFAULT_KEY_SECRET = "test_cashfree_secret";
  public static final String DEFAULT_WEBHOOK_SECRET = "test_cashfree_webhook_secret";

  private final String keySecret;
  private final String webhookSecret;
  private final boolean failCreate;

  public StubCashfreePaymentPort() {
    this(DEFAULT_KEY_SECRET, DEFAULT_WEBHOOK_SECRET, false);
  }

  public StubCashfreePaymentPort(String keySecret, String webhookSecret) {
    this(keySecret, webhookSecret, false);
  }

  public StubCashfreePaymentPort(String keySecret, String webhookSecret, boolean failCreate) {
    this.keySecret = keySecret == null || keySecret.isBlank() ? DEFAULT_KEY_SECRET : keySecret;
    this.webhookSecret =
        webhookSecret == null || webhookSecret.isBlank() ? DEFAULT_WEBHOOK_SECRET : webhookSecret;
    this.failCreate = failCreate;
  }

  @Override
  public CreateOrderResult createOrder(UUID orderId, long amountPaise) {
    if (failCreate) {
      throw new AppException("PAYMENT_INITIATION_FAILED", "Cashfree order creation failed", 502);
    }
    if (amountPaise <= 0) {
      throw new AppException("VALIDATION_ERROR", "amount must be positive", 400);
    }
    String shortId = orderId.toString().replace("-", "").substring(0, 12);
    return new CreateOrderResult("order_stub_" + shortId, amountPaise);
  }

  @Override
  public boolean verifyPaymentSignature(String gatewayOrderId, String paymentId, String signature) {
    if (gatewayOrderId == null) {
      return false;
    }
    if (paymentId == null) {
      return false;
    }
    if (signature == null || signature.isBlank()) {
      return false;
    }
    String expected = signPayment(gatewayOrderId, paymentId);
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String signPayment(String gatewayOrderId, String paymentId) {
    return hmacHex(keySecret, gatewayOrderId + "|" + paymentId);
  }

  @Override
  public boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody) {
    if (signatureHeader == null || rawBody == null) {
      return false;
    }
    String expected = hmacHex(webhookSecret, new String(rawBody, StandardCharsets.UTF_8));
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        signatureHeader.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public RefundResult refund(String gatewayPaymentId, long amountPaise) {
    if (gatewayPaymentId == null || gatewayPaymentId.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "cashfree payment id required for refund", 400);
    }
    if (amountPaise <= 0) {
      throw new AppException("VALIDATION_ERROR", "refund amount must be positive", 400);
    }
    String shortId =
        gatewayPaymentId.length() <= 12
            ? gatewayPaymentId
            : gatewayPaymentId.substring(gatewayPaymentId.length() - 12);
    return new RefundResult("rfnd_stub_" + shortId + "_" + amountPaise, amountPaise);
  }

  public static String hmacHex(String secret, String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("HMAC failed", e);
    }
  }
}

package com.nammamedmate.payment.adapter.out.client;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.CashfreeGatewayPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Deterministic Cashfree stub when keys are blank. */
public final class StubCashfreeGatewayClient implements CashfreeGatewayPort {

  public static final String DEFAULT_KEY_ID = "cf_test_stub";
  public static final String DEFAULT_KEY_SECRET = "test_cashfree_secret";
  public static final String DEFAULT_WEBHOOK_SECRET = "test_cashfree_webhook_secret";

  private final String keyId;
  private final String keySecret;
  private final String webhookSecret;
  private final boolean failCreate;

  public StubCashfreeGatewayClient() {
    this(DEFAULT_KEY_ID, DEFAULT_KEY_SECRET, DEFAULT_WEBHOOK_SECRET, false);
  }

  public StubCashfreeGatewayClient(String keyId, String keySecret, String webhookSecret) {
    this(keyId, keySecret, webhookSecret, false);
  }

  public StubCashfreeGatewayClient(
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
      throw new AppException("CASHFREE_ERROR", "Cashfree order creation failed", 502);
    }
    if (amountPaise <= 0) {
      throw new AppException("VALIDATION_ERROR", "amount must be positive", 400);
    }
    String shortId = orderId.toString().replace("-", "").substring(0, 12);
    String order = "order_stub_" + shortId;
    return new CreateOrderResult(order, "session_stub_" + shortId, amountPaise, keyId);
  }

  @Override
  public boolean verifyPaymentSignature(String gatewayOrderId, String paymentId, String signature) {
    if (gatewayOrderId == null || paymentId == null || signature == null || signature.isBlank()) {
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
    return verifyWebhookSignature(signatureHeader, null, rawBody);
  }

  @Override
  public boolean verifyWebhookSignature(
      String signatureHeader, String timestampHeader, byte[] rawBody) {
    if (signatureHeader == null || rawBody == null) {
      return false;
    }
    String ts = timestampHeader == null ? "" : timestampHeader;
    String payload = ts + new String(rawBody, StandardCharsets.UTF_8);
    String expectedHex = hmacHex(webhookSecret, payload);
    String expectedB64 =
        Base64.getEncoder().encodeToString(hmacBytes(webhookSecret, payload, "HmacSHA256"));
    byte[] sig = signatureHeader.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expectedHex.getBytes(StandardCharsets.UTF_8), sig)
        || MessageDigest.isEqual(expectedB64.getBytes(StandardCharsets.UTF_8), sig);
  }

  @Override
  public String keyId() {
    return keyId;
  }

  @Override
  public RefundResult refund(String gatewayPaymentId, long amountPaise) {
    if (failCreate) {
      throw new AppException("CASHFREE_REFUND_FAILED", "Cashfree refund failed", 502);
    }
    if (gatewayPaymentId == null || gatewayPaymentId.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "gateway payment id required", 400);
    }
    if (amountPaise <= 0) {
      throw new AppException("VALIDATION_ERROR", "amount must be positive", 400);
    }
    String shortId =
        gatewayPaymentId.length() <= 12
            ? gatewayPaymentId
            : gatewayPaymentId.substring(gatewayPaymentId.length() - 12);
    return new RefundResult("rfnd_stub_" + shortId + "_" + amountPaise, amountPaise);
  }

  public static String hmacHex(String secret, String payload) {
    return HexFormat.of().formatHex(hmacBytes(secret, payload, "HmacSHA256"));
  }

  static String hmacHex(String secret, String payload, String algorithm) {
    return HexFormat.of().formatHex(hmacBytes(secret, payload, algorithm));
  }

  static byte[] hmacBytes(String secret, String payload, String algorithm) {
    try {
      Mac mac = Mac.getInstance(algorithm);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
      return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("HMAC failed", e);
    }
  }
}

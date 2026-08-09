package com.nammamedmate.integration.adapter.out.client;

import com.nammamedmate.integration.application.port.out.RazorpayClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Deterministic Razorpay stub when keys are blank (AC-008). */
public final class StubRazorpayClient implements RazorpayClientPort {

  public static final String DEFAULT_KEY_ID = "rzp_test_stub";
  public static final String DEFAULT_KEY_SECRET = "test_razorpay_secret";
  public static final String DEFAULT_WEBHOOK_SECRET = "test_razorpay_webhook_secret";

  private final String webhookSecret;
  private final boolean failCreate;

  public StubRazorpayClient() {
    this(DEFAULT_WEBHOOK_SECRET, false);
  }

  public StubRazorpayClient(String webhookSecret) {
    this(webhookSecret, false);
  }

  public StubRazorpayClient(String webhookSecret, boolean failCreate) {
    this.webhookSecret =
        webhookSecret == null || webhookSecret.isBlank() ? DEFAULT_WEBHOOK_SECRET : webhookSecret;
    this.failCreate = failCreate;
  }

  @Override
  public CreateOrderResult createOrder(
      long amountPaise, String currency, String receipt, Map<String, String> notes) {
    if (failCreate) {
      throw new AppException("RAZORPAY_UNAVAILABLE", "Razorpay API returned error", 503);
    }
    String id = "order_stub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    return new CreateOrderResult(id, amountPaise, blankToInr(currency), receipt, "created");
  }

  private static String blankToInr(String currency) {
    return currency == null ? "INR" : currency;
  }

  @Override
  public CaptureResult capturePayment(String razorpayPaymentId, long amountPaise) {
    if (failCreate) {
      throw new AppException("RAZORPAY_UNAVAILABLE", "Razorpay capture failed", 503);
    }
    if (razorpayPaymentId == null) {
      throw new AppException("VALIDATION_ERROR", "payment id required", 400);
    }
    if (razorpayPaymentId.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "payment id required", 400);
    }
    return new CaptureResult(razorpayPaymentId.trim(), "captured");
  }

  @Override
  public UpiVerifyResult verifyUpi(String vpa) {
    String normalized = vpa.trim();
    int at = normalized.indexOf('@');
    String handle = at < 0 ? "" : normalized.substring(at + 1);
    boolean valid =
        "okicici".equalsIgnoreCase(handle) || (at > 0 && handle.matches("[a-zA-Z]{2,64}"));
    String local = at < 0 ? normalized : normalized.substring(0, at);
    String name = valid ? local.replace('.', ' ').replace('_', ' ').toUpperCase(Locale.ROOT) : null;
    return new UpiVerifyResult(normalized, valid, name);
  }

  @Override
  public boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody) {
    if (signatureHeader == null || rawBody == null) {
      return false;
    }
    String expected =
        RazorpayHmac.hmacHex(webhookSecret, new String(rawBody, StandardCharsets.UTF_8));
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        signatureHeader.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String mode() {
    return "TEST";
  }
}

package com.nammamedmate.integration.adapter.out.client;

import com.nammamedmate.integration.application.port.out.CashfreeClientPort;
import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Deterministic Cashfree stub when keys are blank (AC-006). */
public final class StubCashfreeClient implements CashfreeClientPort {

  public static final String DEFAULT_APP_ID = "cf_test_stub";
  public static final String DEFAULT_SECRET_KEY = "test_cashfree_secret";
  public static final String DEFAULT_WEBHOOK_SECRET = "test_cashfree_webhook_secret";

  /**
   * @deprecated use {@link #DEFAULT_APP_ID}
   */
  public static final String DEFAULT_KEY_ID = DEFAULT_APP_ID;

  /**
   * @deprecated use {@link #DEFAULT_SECRET_KEY}
   */
  public static final String DEFAULT_KEY_SECRET = DEFAULT_SECRET_KEY;

  private final String webhookSecret;
  private final boolean failCreate;

  public StubCashfreeClient() {
    this(DEFAULT_WEBHOOK_SECRET, false);
  }

  public StubCashfreeClient(String webhookSecret) {
    this(webhookSecret, false);
  }

  public StubCashfreeClient(String webhookSecret, boolean failCreate) {
    this.webhookSecret =
        webhookSecret == null || webhookSecret.isBlank() ? DEFAULT_WEBHOOK_SECRET : webhookSecret;
    this.failCreate = failCreate;
  }

  @Override
  public CreateOrderResult createOrder(
      long amountPaise, String currency, String receipt, Map<String, String> notes) {
    if (failCreate) {
      throw new AppException("CASHFREE_UNAVAILABLE", "Cashfree API returned error", 503);
    }
    String id = "order_stub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    String session = "session_stub_" + id.substring(Math.max(0, id.length() - 12));
    return new CreateOrderResult(
        id, session, amountPaise, blankToInr(currency), receipt, "created");
  }

  private static String blankToInr(String currency) {
    return currency == null ? "INR" : currency;
  }

  @Override
  public CaptureResult capturePayment(String gatewayPaymentId, long amountPaise) {
    if (failCreate) {
      throw new AppException("CASHFREE_UNAVAILABLE", "Cashfree capture failed", 503);
    }
    if (gatewayPaymentId == null || gatewayPaymentId.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "payment id required", 400);
    }
    return new CaptureResult(gatewayPaymentId.trim(), "captured");
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
    return verifyWebhookSignature(signatureHeader, null, rawBody);
  }

  @Override
  public boolean verifyWebhookSignature(
      String signatureHeader, String timestampHeader, byte[] rawBody) {
    return CashfreeHmac.verify(webhookSecret, signatureHeader, timestampHeader, rawBody);
  }

  @Override
  public String mode() {
    return "TEST";
  }
}

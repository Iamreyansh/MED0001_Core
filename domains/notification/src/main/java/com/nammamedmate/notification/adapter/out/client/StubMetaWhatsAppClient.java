package com.nammamedmate.notification.adapter.out.client;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.port.out.MetaWhatsAppClientPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Deterministic Meta Cloud API stub for WhatsApp send + template submit + HMAC verify. */
public class StubMetaWhatsAppClient implements MetaWhatsAppClientPort {

  public static final String DEFAULT_APP_SECRET = "test_whatsapp_app_secret";

  private final String appSecret;
  private final AtomicBoolean failSend = new AtomicBoolean(false);
  private final AtomicBoolean failSubmit = new AtomicBoolean(false);
  private final AtomicInteger sendCalls = new AtomicInteger();
  private final AtomicInteger submitCalls = new AtomicInteger();

  public StubMetaWhatsAppClient() {
    this(DEFAULT_APP_SECRET);
  }

  public StubMetaWhatsAppClient(String appSecret) {
    this.appSecret = appSecret == null || appSecret.isBlank() ? DEFAULT_APP_SECRET : appSecret;
  }

  public void setFailSend(boolean value) {
    failSend.set(value);
  }

  public void setFailSubmit(boolean value) {
    failSubmit.set(value);
  }

  public int sendCallCount() {
    return sendCalls.get();
  }

  public int submitCallCount() {
    return submitCalls.get();
  }

  public void reset() {
    failSend.set(false);
    failSubmit.set(false);
    sendCalls.set(0);
    submitCalls.set(0);
  }

  public String sign(byte[] body) {
    return "sha256=" + hmacHex(appSecret, body);
  }

  @Override
  public SendResult sendTemplate(SendRequest request) {
    sendCalls.incrementAndGet();
    if (failSend.get()) {
      return SendResult.fail("Meta API unavailable");
    }
    return SendResult.ok("wamid." + Ids.newId().toString().replace("-", ""));
  }

  @Override
  public SubmitTemplateResult submitTemplate(SubmitTemplateRequest request) {
    submitCalls.incrementAndGet();
    if (failSubmit.get()) {
      return SubmitTemplateResult.fail("Meta template submit failed");
    }
    return SubmitTemplateResult.ok("meta_tpl_" + request.name().toLowerCase());
  }

  @Override
  public boolean verifyWebhookSignature(String signatureHeader, byte[] rawBody) {
    if (signatureHeader == null || signatureHeader.isBlank()) {
      return false;
    }
    byte[] body = rawBody == null ? new byte[0] : rawBody;
    String expected = "sha256=" + hmacHex(appSecret, body);
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
  }

  static String hmacHex(String secret, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(body == null ? new byte[0] : body));
    } catch (Exception e) {
      throw new IllegalStateException("HMAC failed", e);
    }
  }
}

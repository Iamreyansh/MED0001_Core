package com.nammamedmate.pharmacy.domain;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Shared HMAC-SHA256 hex helper for pharmacy webhooks. */
public final class WebhookHmac {

  private WebhookHmac() {}

  public static String hmacSha256Hex(String secret, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(body == null ? new byte[0] : body));
    } catch (Exception e) {
      throw new IllegalStateException("HMAC-SHA256 failed", e);
    }
  }
}

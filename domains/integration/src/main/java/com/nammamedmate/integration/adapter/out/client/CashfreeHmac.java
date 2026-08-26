package com.nammamedmate.integration.adapter.out.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cashfree webhook HMAC-SHA256. Signed payload is {@code timestamp + rawBody} when timestamp is
 * present (headers {@code x-webhook-signature}, {@code x-webhook-timestamp}).
 */
public final class CashfreeHmac {

  private CashfreeHmac() {}

  public static String hmacHex(String secret, String payload) {
    return hmacHex(secret, payload, "HmacSHA256");
  }

  public static String signedPayload(String timestamp, String rawBody) {
    String ts = timestamp == null ? "" : timestamp;
    String body = rawBody == null ? "" : rawBody;
    return ts + body;
  }

  public static boolean verify(
      String secret, String signatureHeader, String timestamp, byte[] rawBody) {
    if (signatureHeader == null || rawBody == null || secret == null || secret.isBlank()) {
      return false;
    }
    String payload = signedPayload(timestamp, new String(rawBody, StandardCharsets.UTF_8));
    String expectedHex = hmacHex(secret, payload);
    byte[] expectedBytes = macBytes(secret, payload, "HmacSHA256");
    String expectedB64 = Base64.getEncoder().encodeToString(expectedBytes);
    byte[] sig = signatureHeader.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expectedHex.getBytes(StandardCharsets.UTF_8), sig)
        || MessageDigest.isEqual(expectedB64.getBytes(StandardCharsets.UTF_8), sig);
  }

  /** Visible for tests — pass a bogus algorithm to hit the failure path. */
  static String hmacHex(String secret, String payload, String algorithm) {
    return HexFormat.of().formatHex(macBytes(secret, payload, algorithm));
  }

  private static byte[] macBytes(String secret, String payload, String algorithm) {
    try {
      Mac mac = Mac.getInstance(algorithm);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
      return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("HMAC failed", e);
    }
  }
}

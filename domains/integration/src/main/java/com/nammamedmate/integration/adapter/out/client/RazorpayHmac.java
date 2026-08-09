package com.nammamedmate.integration.adapter.out.client;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class RazorpayHmac {

  private RazorpayHmac() {}

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

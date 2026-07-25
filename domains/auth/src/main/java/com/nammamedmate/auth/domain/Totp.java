package com.nammamedmate.auth.domain;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30s step). Accepts current and previous window. */
public final class Totp {

  public static final int DIGITS = 6;
  public static final int PERIOD_SECONDS = 30;
  private static final String HMAC = "HmacSHA1";

  @FunctionalInterface
  interface MacFactory {
    Mac create() throws GeneralSecurityException;
  }

  private Totp() {}

  public static String generate(byte[] key, Instant instant) {
    long counter = instant.getEpochSecond() / PERIOD_SECONDS;
    return formatCode(hotp(key, counter));
  }

  /** True if code matches current or previous 30s window. */
  public static boolean verify(byte[] key, String code, Instant instant) {
    if (code == null || !code.matches("\\d{6}")) {
      return false;
    }
    long counter = instant.getEpochSecond() / PERIOD_SECONDS;
    return code.equals(formatCode(hotp(key, counter)))
        || code.equals(formatCode(hotp(key, counter - 1)));
  }

  static int hotp(byte[] key, long counter) {
    return hotp(key, counter, () -> Mac.getInstance(HMAC));
  }

  static int hotp(byte[] key, long counter, MacFactory macFactory) {
    try {
      Mac mac = macFactory.create();
      mac.init(new SecretKeySpec(key, HMAC));
      byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
      int offset = hash[hash.length - 1] & 0x0f;
      int binary =
          ((hash[offset] & 0x7f) << 24)
              | ((hash[offset + 1] & 0xff) << 16)
              | ((hash[offset + 2] & 0xff) << 8)
              | (hash[offset + 3] & 0xff);
      return binary % 1_000_000;
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("HOTP failed", ex);
    }
  }

  private static String formatCode(int value) {
    return String.format("%0" + DIGITS + "d", value);
  }
}

package com.nammamedmate.auth.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Opaque refresh-token generation and SHA-256 hex hashing (sessions.refresh_token_hash). */
public final class RefreshTokens {

  @FunctionalInterface
  interface DigestFactory {
    MessageDigest create() throws NoSuchAlgorithmException;
  }

  private RefreshTokens() {}

  public static String generate(SecureRandom secureRandom) {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String sha256Hex(String value) {
    return sha256Hex(value, () -> MessageDigest.getInstance("SHA-256"));
  }

  static String sha256Hex(String value, DigestFactory digestFactory) {
    try {
      MessageDigest digest = digestFactory.create();
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }
}

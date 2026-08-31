package com.nammamedmate.auth.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Opaque pharmacy staff invite tokens; only SHA-256 hashes are persisted. */
public final class PharmacyStaffTokens {

  private static final SecureRandom RANDOM = new SecureRandom();

  @FunctionalInterface
  interface DigestFactory {
    MessageDigest create() throws NoSuchAlgorithmException;
  }

  private PharmacyStaffTokens() {}

  public static String generate() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String sha256Hex(String value) {
    return sha256Hex(value, () -> MessageDigest.getInstance("SHA-256"));
  }

  static String sha256Hex(String value, DigestFactory digestFactory) {
    try {
      MessageDigest digest = digestFactory.create();
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }
}

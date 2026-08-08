package com.nammamedmate.rider.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Crypto 4-digit OTPs; SHA-256 hex for DB (plaintext lives in Redis TTL). */
public final class AssignmentOtps {

  @FunctionalInterface
  public interface DigestFactory {
    MessageDigest create() throws NoSuchAlgorithmException;
  }

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final DigestFactory DEFAULT = () -> MessageDigest.getInstance("SHA-256");
  private static volatile DigestFactory digestFactory = DEFAULT;

  private AssignmentOtps() {}

  /** Test-only hook for SHA-256 failure path. */
  public static void setDigestFactory(DigestFactory factory) {
    digestFactory = factory == null ? DEFAULT : factory;
  }

  public static String generate() {
    return String.format("%04d", RANDOM.nextInt(10_000));
  }

  public static String hash(String otp) {
    if (otp == null) {
      return "";
    }
    try {
      MessageDigest md = digestFactory.create();
      byte[] digest = md.digest(otp.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public static boolean matches(String otp, String hash) {
    if (otp == null) {
      return false;
    }
    if (hash == null || hash.isBlank()) {
      return false;
    }
    return hash.equals(hash(otp));
  }
}

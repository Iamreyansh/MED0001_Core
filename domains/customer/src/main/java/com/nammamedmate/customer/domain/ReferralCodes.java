package com.nammamedmate.customer.domain;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.function.Predicate;

/** Referral codes: {@code MED} + 4 uppercase base-36 chars (7 total). */
public final class ReferralCodes {

  private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int MAX_ATTEMPTS = 32;

  private ReferralCodes() {}

  public static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    return raw.trim().toUpperCase(Locale.ROOT);
  }

  /**
   * Apply-time format check: a 7-character alphanumeric code. Prefix is not enforced here so an
   * unknown-but-well-formed code (e.g. no {@code MED} prefix) yields 404 REFERRAL_CODE_NOT_FOUND at
   * lookup rather than 400, matching the story's contract.
   */
  public static boolean isValidFormat(String code) {
    String normalized = normalize(code);
    if (normalized == null || normalized.length() != 7) {
      return false;
    }
    for (int i = 0; i < 7; i++) {
      if (ALPHABET.indexOf(normalized.charAt(i)) < 0) {
        return false;
      }
    }
    return true;
  }

  /** Generate a unique code; {@code taken} returns true when the candidate already exists. */
  public static String generateUnique(Predicate<String> taken) {
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      String candidate = "MED" + randomSuffix(4);
      if (!taken.test(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("unable to generate unique referral code");
  }

  private static String randomSuffix(int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return sb.toString();
  }
}

package com.nammamedmate.auth.domain;

/** Normalises and categorises login identifiers (email or +91 phone). */
public final class LoginIdentifiers {

  private LoginIdentifiers() {}

  public enum Type {
    EMAIL,
    PHONE
  }

  public record Normalised(String value, Type type) {}

  /**
   * Returns a normalised identifier or {@code null} if blank.
   *
   * @throws IllegalArgumentException if the identifier is neither a plausible email nor a valid
   *     Indian mobile (+91)
   */
  public static Normalised normalise(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String trimmed = raw.strip();
    if (trimmed.contains("@")) {
      String email = trimmed.toLowerCase();
      if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
        throw new IllegalArgumentException("Malformed email identifier");
      }
      return new Normalised(email, Type.EMAIL);
    }
    String phone = trimmed.replace(" ", "");
    if (!PhoneNumbers.isValidIndianMobile(phone)) {
      throw new IllegalArgumentException("Malformed phone identifier");
    }
    return new Normalised(phone, Type.PHONE);
  }
}

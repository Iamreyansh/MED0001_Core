package com.nammamedmate.pharmacy.domain;

import java.util.regex.Pattern;

public final class PharmacyPassword {

  private static final Pattern UPPER = Pattern.compile("[A-Z]");
  private static final Pattern DIGIT = Pattern.compile("[0-9]");
  private static final Pattern SPECIAL = Pattern.compile("[^A-Za-z0-9]");

  private PharmacyPassword() {}

  public static String requireValid(String raw) {
    if (raw == null
        || raw.length() < 8
        || !UPPER.matcher(raw).find()
        || !DIGIT.matcher(raw).find()
        || !SPECIAL.matcher(raw).find()) {
      throw new IllegalArgumentException("INVALID_PASSWORD_STRENGTH");
    }
    return raw;
  }
}

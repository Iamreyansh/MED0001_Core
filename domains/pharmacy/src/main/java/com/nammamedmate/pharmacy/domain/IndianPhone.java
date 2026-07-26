package com.nammamedmate.pharmacy.domain;

import java.util.regex.Pattern;

public final class IndianPhone {

  private static final Pattern PATTERN = Pattern.compile("^\\+91[6-9][0-9]{9}$");

  private IndianPhone() {}

  public static String requireValid(String raw) {
    if (raw == null || !PATTERN.matcher(raw.trim()).matches()) {
      throw new IllegalArgumentException("INVALID_PHONE");
    }
    return raw.trim();
  }
}

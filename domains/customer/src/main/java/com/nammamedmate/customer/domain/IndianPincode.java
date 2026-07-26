package com.nammamedmate.customer.domain;

import java.util.regex.Pattern;

/** Indian postal code: exactly 6 digits, first digit 1-9. */
public final class IndianPincode {

  private static final Pattern PATTERN = Pattern.compile("^[1-9][0-9]{5}$");

  private IndianPincode() {}

  public static String requireValid(String raw) {
    if (raw == null || !PATTERN.matcher(raw.trim()).matches()) {
      throw new IllegalArgumentException("pincode must be exactly 6 digits");
    }
    return raw.trim();
  }
}

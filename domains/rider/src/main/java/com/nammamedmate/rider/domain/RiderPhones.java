package com.nammamedmate.rider.domain;

import java.util.regex.Pattern;

public final class RiderPhones {

  private static final Pattern TEN_DIGIT = Pattern.compile("^[6-9]\\d{9}$");
  private static final Pattern E164 = Pattern.compile("^\\+91[6-9]\\d{9}$");

  private RiderPhones() {}

  /** Accepts 10-digit or +91…; returns E.164 or null if invalid. */
  public static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    String p = raw.trim().replace(" ", "").replace("-", "");
    if (TEN_DIGIT.matcher(p).matches()) {
      return "+91" + p;
    }
    if (p.matches("^91[6-9]\\d{9}$")) {
      return "+" + p;
    }
    if (E164.matcher(p).matches()) {
      return p;
    }
    return null;
  }

  public static boolean isValid(String e164) {
    return e164 != null && E164.matcher(e164).matches();
  }
}

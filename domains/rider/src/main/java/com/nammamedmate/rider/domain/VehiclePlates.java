package com.nammamedmate.rider.domain;

import java.util.regex.Pattern;

public final class VehiclePlates {

  private static final Pattern INDIAN_RTO =
      Pattern.compile("^[A-Z]{2}[0-9]{1,2}[A-Z]{1,3}[0-9]{4}$");

  private VehiclePlates() {}

  /** Strip dashes/spaces, uppercase; return null if blank. */
  public static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    String cleaned = raw.trim().replace("-", "").replace(" ", "").toUpperCase();
    return cleaned.isEmpty() ? null : cleaned;
  }

  public static boolean isValid(String normalized) {
    return normalized != null && INDIAN_RTO.matcher(normalized).matches();
  }
}

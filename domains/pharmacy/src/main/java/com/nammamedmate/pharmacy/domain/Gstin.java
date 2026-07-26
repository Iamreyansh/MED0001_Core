package com.nammamedmate.pharmacy.domain;

import java.util.Set;

/** GSTIN format + MOD-36 checksum (story Notes). */
public final class Gstin {

  private static final String BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final Set<String> STATE_CODES =
      Set.of(
          "01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13", "14", "15",
          "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30",
          "31", "32", "33", "34", "35", "36", "37");

  private Gstin() {}

  public static String requireValid(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("INVALID_GSTIN");
    }
    String gstin = raw.trim().toUpperCase();
    if (gstin.length() != 15 || !gstin.chars().allMatch(Gstin::isBase36)) {
      throw new IllegalArgumentException("INVALID_GSTIN");
    }
    String state = gstin.substring(0, 2);
    if (!STATE_CODES.contains(state)) {
      throw new IllegalArgumentException("INVALID_GSTIN");
    }
    String pan = gstin.substring(2, 12);
    try {
      Pan.requireValid(pan);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("INVALID_GSTIN");
    }
    if (!Character.isDigit(gstin.charAt(12))) {
      throw new IllegalArgumentException("INVALID_GSTIN");
    }
    if (gstin.charAt(13) != 'Z') {
      throw new IllegalArgumentException("INVALID_GSTIN");
    }
    if (gstin.charAt(14) != checkDigit(gstin.substring(0, 14))) {
      throw new IllegalArgumentException("INVALID_GSTIN");
    }
    return gstin;
  }

  public static String stateCode(String gstin) {
    return gstin.substring(0, 2);
  }

  static char checkDigit(String first14) {
    int factor = 1;
    int total = 0;
    for (int i = 0; i < first14.length(); i++) {
      int codePoint = BASE36.indexOf(first14.charAt(i));
      int product = factor * codePoint;
      total += (product / 36) + (product % 36);
      factor = factor == 1 ? 2 : 1;
    }
    return BASE36.charAt((36 - (total % 36)) % 36);
  }

  private static boolean isBase36(int c) {
    return BASE36.indexOf(c) >= 0;
  }
}

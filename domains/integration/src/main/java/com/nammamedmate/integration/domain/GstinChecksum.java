package com.nammamedmate.integration.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * GSTIN format + Luhn mod-36 check digit (15th character over first 14).
 *
 * <p>ponytail: matches GSTN developer-network algorithm; format regex is local-only gate before
 * API.
 */
public final class GstinChecksum {

  private static final String CODEPOINTS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final Pattern FORMAT =
      Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");

  private GstinChecksum() {}

  public static boolean isValid(String gstin) {
    if (gstin == null || gstin.length() != 15) {
      return false;
    }
    String upper = gstin.trim().toUpperCase(Locale.ROOT);
    if (!FORMAT.matcher(upper).matches()) {
      return false;
    }
    return upper.charAt(14) == checkDigit(upper.substring(0, 14));
  }

  /** Check digit for the first 14 characters (Luhn mod 36). */
  public static char checkDigit(String first14) {
    if (first14 == null || first14.length() != 14) {
      throw new IllegalArgumentException("GSTIN prefix must be 14 characters");
    }
    int factor = 2;
    int sum = 0;
    int mod = CODEPOINTS.length();
    char[] chars = first14.toUpperCase(Locale.ROOT).toCharArray();
    for (int i = chars.length - 1; i >= 0; i--) {
      int codePoint = CODEPOINTS.indexOf(chars[i]);
      if (codePoint < 0) {
        throw new IllegalArgumentException("Invalid GSTIN character: " + chars[i]);
      }
      int product = factor * codePoint;
      factor = factor == 2 ? 1 : 2;
      sum += (product / mod) + (product % mod);
    }
    int checkCodePoint = (mod - (sum % mod)) % mod;
    return CODEPOINTS.charAt(checkCodePoint);
  }
}

package com.nammamedmate.customer.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;
import java.util.regex.Pattern;

/** UPI VPA helpers: format validation and display masking. */
public final class UpiVpa {

  private static final Pattern VPA = Pattern.compile("^[a-zA-Z0-9._-]{2,64}@[a-zA-Z0-9._-]{2,32}$");

  private UpiVpa() {}

  public static String requireValid(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "upi_id is required", 400);
    }
    String trimmed = raw.trim();
    if (trimmed.length() > 100) {
      throw new AppException("VALIDATION_ERROR", "upi_id max length is 100", 400);
    }
    if (!VPA.matcher(trimmed).matches()) {
      throw new AppException("VALIDATION_ERROR", "upi_id format is invalid", 400);
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }

  /** Mask local part: {@code ramesh@okaxis} → {@code ***@okaxis}. */
  public static String maskHandle(String vpa) {
    int at = vpa.indexOf('@');
    if (at < 0) {
      return "***";
    }
    return "***" + vpa.substring(at);
  }
}

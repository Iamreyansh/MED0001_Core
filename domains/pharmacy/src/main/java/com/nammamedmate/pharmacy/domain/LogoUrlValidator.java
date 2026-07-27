package com.nammamedmate.pharmacy.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;
import java.util.regex.Pattern;

public final class LogoUrlValidator {

  private static final Pattern HTTP_URL = Pattern.compile("^https?://.+", Pattern.CASE_INSENSITIVE);

  private LogoUrlValidator() {}

  /** ponytail: extension + URL shape only; no 2MB CDN fetch. */
  public static void requireValid(String logoUrl) {
    if (logoUrl == null || logoUrl.isBlank()) {
      return;
    }
    String url = logoUrl.trim();
    if (!HTTP_URL.matcher(url).matches()) {
      throw new AppException("INVALID_LOGO", "logo_url must be an http(s) URL", 400);
    }
    String lower = url.toLowerCase(Locale.ROOT);
    if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
      throw new AppException("INVALID_LOGO", "Logo must be PNG or JPG", 400);
    }
  }
}

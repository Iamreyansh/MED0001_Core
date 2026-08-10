package com.nammamedmate.marketing.adapter.out.client;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.marketing.application.port.out.BannerImageValidatorPort;
import java.util.Locale;
import java.util.Set;

/**
 * Stub validator for unit tests / local: accepts https JPG/PNG URLs; allowlisted hosts skip size
 * checks; URLs with sentinel tokens simulate failures.
 */
public class StubBannerImageValidator implements BannerImageValidatorPort {

  private static final Set<String> ALLOWLIST =
      Set.of("cdn.nammamedmate.com", "cdn.test", "localhost", "127.0.0.1");

  @Override
  public void validate(String imageUrl) {
    if (imageUrl == null || imageUrl.isBlank()) {
      throw new AppException("INVALID_IMAGE_URL", "image_url is required", 422);
    }
    String trimmed = imageUrl.trim();
    String lower = trimmed.toLowerCase(Locale.ROOT);
    if (!lower.startsWith("https://")) {
      throw new AppException("INVALID_IMAGE_URL", "image_url must be https", 422);
    }
    if (lower.contains("404") || lower.contains("/missing")) {
      throw new AppException("INVALID_IMAGE_URL", "Image URL unreachable or not JPG/PNG", 422);
    }
    if (lower.contains("too-large") || lower.contains("over2mb")) {
      throw new AppException("IMAGE_TOO_LARGE", "Image exceeds 2 MB", 422);
    }
    String path = lower;
    int q = path.indexOf('?');
    if (q >= 0) {
      path = path.substring(0, q);
    }
    if (!path.endsWith(".jpg") && !path.endsWith(".jpeg") && !path.endsWith(".png")) {
      throw new AppException("INVALID_IMAGE_URL", "Image URL unreachable or not JPG/PNG", 422);
    }
    String host = hostOf(lower);
    if (ALLOWLIST.contains(host)) {
      return;
    }
    // Non-allowlisted: accept when extension ok (stub cannot HEAD); reject bogus schemes already
    // done
  }

  private static String hostOf(String lowerUrl) {
    int start = "https://".length();
    int slash = lowerUrl.indexOf('/', start);
    String hostPort = slash < 0 ? lowerUrl.substring(start) : lowerUrl.substring(start, slash);
    int colon = hostPort.indexOf(':');
    return colon < 0 ? hostPort : hostPort.substring(0, colon);
  }
}

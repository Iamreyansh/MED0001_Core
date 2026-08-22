package com.nammamedmate.marketing.adapter.out.client;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.marketing.application.port.out.BannerImageValidatorPort;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Real HEAD/GET image validation via HttpURLConnection. */
@Component
@ConditionalOnProperty(name = "medmate.marketing.banner.image-validation", havingValue = "http")
public class HttpBannerImageValidator implements BannerImageValidatorPort {

  private static final long MAX_BYTES = 2L * 1024 * 1024;
  private static final Set<String> OK_TYPES =
      Set.of("image/jpeg", "image/jpg", "image/png", "image/x-png");

  @Override
  public void validate(String imageUrl) {
    if (imageUrl == null || imageUrl.isBlank()) {
      throw new AppException("INVALID_IMAGE_URL", "image_url is required", 422);
    }
    String trimmed = imageUrl.trim();
    String scheme = trimmed.toLowerCase(Locale.ROOT);
    boolean httpish = scheme.startsWith("https://") || scheme.startsWith("http://");
    if (!httpish) {
      throw new AppException("INVALID_IMAGE_URL", "image_url must be http(s)", 422);
    }
    rejectUnsafeHost(trimmed);
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) URI.create(trimmed).toURL().openConnection();
      conn.setInstanceFollowRedirects(false);
      conn.setConnectTimeout(3_000);
      conn.setReadTimeout(3_000);
      conn.setRequestMethod("HEAD");
      int code = conn.getResponseCode();
      if (code == 405) {
        conn.disconnect();
        conn = (HttpURLConnection) URI.create(trimmed).toURL().openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(3_000);
        conn.setReadTimeout(3_000);
        conn.setRequestMethod("GET");
        code = conn.getResponseCode();
      }
      if (code >= 400) {
        throw new AppException("INVALID_IMAGE_URL", "Image URL unreachable or not JPG/PNG", 422);
      }
      long len = conn.getContentLengthLong();
      if (len > MAX_BYTES) {
        throw new AppException("IMAGE_TOO_LARGE", "Image exceeds 2 MB", 422);
      }
      String ct = conn.getContentType();
      String type = ct == null ? "" : ct.split(";")[0].trim().toLowerCase(Locale.ROOT);
      if (!OK_TYPES.contains(type) && !hasImageExtension(trimmed)) {
        throw new AppException("INVALID_IMAGE_URL", "Image URL unreachable or not JPG/PNG", 422);
      }
    } catch (AppException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new AppException("INVALID_IMAGE_URL", "Image URL unreachable or not JPG/PNG", 422);
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  static void rejectUnsafeHost(String imageUrl) {
    URI uri;
    try {
      uri = URI.create(imageUrl);
    } catch (IllegalArgumentException ex) {
      throw new AppException("INVALID_IMAGE_URL", "image_url must be http(s)", 422);
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new AppException("INVALID_IMAGE_URL", "image_url must be http(s)", 422);
    }
    String h = host.toLowerCase(Locale.ROOT);
    if ("metadata.google.internal".equals(h)
        || h.endsWith(".internal")
        || "169.254.169.254".equals(h)) {
      throw new AppException("INVALID_IMAGE_URL", "Image URL host is not allowed", 422);
    }
    try {
      for (InetAddress addr : InetAddress.getAllByName(host)) {
        if (addr.isAnyLocalAddress()
            || addr.isLinkLocalAddress()
            || addr.isSiteLocalAddress()
            || addr.isMulticastAddress()) {
          throw new AppException("INVALID_IMAGE_URL", "Image URL host is not allowed", 422);
        }
      }
    } catch (AppException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new AppException("INVALID_IMAGE_URL", "Image URL unreachable or not JPG/PNG", 422);
    }
  }

  private static boolean hasImageExtension(String url) {
    String path = url.toLowerCase(Locale.ROOT);
    int q = path.indexOf('?');
    if (q >= 0) {
      path = path.substring(0, q);
    }
    return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png");
  }
}

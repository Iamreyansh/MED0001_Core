package com.nammamedmate.pharmacy.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.pharmacy.application.port.out.KycObjectStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore;
import com.nammamedmate.pharmacy.application.port.out.VirusScanner;
import com.nammamedmate.pharmacy.domain.LogoUrlValidator;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PharmacyLogoService {

  static final int MAX_BYTES = 2 * 1024 * 1024;
  static final int UPLOAD_LIMIT = 10;
  static final int WINDOW = 60;
  private static final Set<String> PNG_JPEG = Set.of("image/png", "image/jpeg");
  private static final Pattern PUBLIC_FILE =
      Pattern.compile(
          "^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.(png|jpg|jpeg)$",
          Pattern.CASE_INSENSITIVE);

  private final PharmacyProfileStore profiles;
  private final KycObjectStore objects;
  private final VirusScanner virusScanner;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public PharmacyLogoService(
      PharmacyProfileStore profiles,
      KycObjectStore objects,
      VirusScanner virusScanner,
      RateLimiter rateLimiter,
      Clock clock) {
    this.profiles = profiles;
    this.objects = objects;
    this.virusScanner = virusScanner;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record PublicLogo(byte[] bytes, String contentType) {
    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  public record PublicLogoRef(UUID pharmacyId, String ext) {}

  @Transactional
  public Map<String, Object> uploadLogo(
      MedmatePrincipal principal,
      byte[] fileBytes,
      String originalFileName,
      String contentType,
      Function<String, String> publicUrlForFile) {
    PharmacyProfileService.requireOwner(principal);
    UUID pharmacyId = principal.pharmacyId();
    if (!rateLimiter.tryAcquire("pharmacy:profile:logo:" + pharmacyId, UPLOAD_LIMIT, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
    if (fileBytes == null || fileBytes.length == 0) {
      throw new AppException("INVALID_LOGO", "Choose a PNG or JPG image", 400);
    }
    if (fileBytes.length > MAX_BYTES) {
      throw new AppException("INVALID_LOGO", "Logo must be 2 MB or smaller", 400);
    }
    String mime = resolveImageMime(fileBytes, contentType);
    try {
      virusScanner.scan(fileBytes, originalFileName);
    } catch (VirusScanner.VirusScanException e) {
      throw new AppException("INVALID_LOGO", "File failed virus scan", 400);
    }
    String ext = "image/png".equals(mime) ? "png" : "jpg";
    objects.put(logoKey(pharmacyId, ext), fileBytes, mime);
    for (String other : new String[] {"png", "jpg", "jpeg"}) {
      if (!other.equals(ext)) {
        objects.delete(logoKey(pharmacyId, other));
      }
    }
    String publicLogoUrl = publicUrlForFile.apply(publicFileName(pharmacyId, mime));
    LogoUrlValidator.requireValid(publicLogoUrl);
    profiles.updateLogoUrl(pharmacyId, publicLogoUrl, clock.instant());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("logo_url", publicLogoUrl);
    data.put("updated_fields", List.of("logo_url"));
    data.put("message", "Logo updated.");
    return data;
  }

  public PublicLogo readPublicLogo(UUID pharmacyId, String ext) {
    if (pharmacyId == null || ext == null) {
      return null;
    }
    String normalized = normalizeExt(ext);
    if (normalized == null) {
      return null;
    }
    byte[] bytes = objects.get(logoKey(pharmacyId, normalized));
    if (bytes == null || bytes.length == 0) {
      return null;
    }
    return new PublicLogo(bytes, "png".equals(normalized) ? "image/png" : "image/jpeg");
  }

  public static PublicLogoRef parsePublicFileName(String fileName) {
    if (fileName == null || fileName.isBlank()) {
      return null;
    }
    Matcher matcher = PUBLIC_FILE.matcher(fileName.trim());
    if (!matcher.matches()) {
      return null;
    }
    return new PublicLogoRef(UUID.fromString(matcher.group(1)), matcher.group(2));
  }

  static String publicFileName(UUID pharmacyId, String mime) {
    return pharmacyId + ("image/png".equals(mime) ? ".png" : ".jpg");
  }

  static String resolveImageMime(byte[] fileBytes, String contentType) {
    String sniffed = PharmacyKycService.sniffMime(fileBytes);
    if (sniffed == null || !PNG_JPEG.contains(sniffed)) {
      throw new AppException("INVALID_LOGO", "Logo must be PNG or JPG", 400);
    }
    String claimed = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase();
    if (!claimed.isEmpty() && PNG_JPEG.contains(claimed) && !claimed.equals(sniffed)) {
      throw new AppException("INVALID_LOGO", "File content does not match declared type", 400);
    }
    return sniffed;
  }

  private static String logoKey(UUID pharmacyId, String ext) {
    return StorageObjectKeys.key(StorageObjectKeys.PHARMACIES, pharmacyId + "/logo." + ext);
  }

  private static String normalizeExt(String ext) {
    String value = ext.trim().toLowerCase();
    if ("png".equals(value)) {
      return "png";
    }
    if ("jpg".equals(value) || "jpeg".equals(value)) {
      return "jpg";
    }
    return null;
  }
}

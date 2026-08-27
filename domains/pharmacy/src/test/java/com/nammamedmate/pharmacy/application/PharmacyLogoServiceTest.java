package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pharmacy.application.port.out.KycObjectStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore;
import com.nammamedmate.pharmacy.application.port.out.VirusScanner;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PharmacyLogoServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");
  private static final UUID PID = Ids.newId();

  private PharmacyProfileStore profiles;
  private MemoryObjects objects;
  private VirusScanner virusScanner;
  private RateLimiter rateLimiter;
  private PharmacyLogoService service;

  @BeforeEach
  void setUp() {
    profiles = mock(PharmacyProfileStore.class);
    objects = new MemoryObjects();
    virusScanner = mock(VirusScanner.class);
    rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    service =
        new PharmacyLogoService(
            profiles, objects, virusScanner, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void uploadStoresPngAndReturnsPublicUrl() {
    byte[] png = PharmacyKycServiceTest.pngSample();
    Map<String, Object> data =
        service.uploadLogo(
            owner(), png, "board.png", "image/png", name -> "https://api.example/" + name);

    String expected = "https://api.example/" + PID + ".png";
    assertThat(data.get("logo_url")).isEqualTo(expected);
    assertThat(objects.stored.get("pharmacies/" + PID + "/logo.png")).isEqualTo(png);
    verify(profiles).updateLogoUrl(eq(PID), eq(expected), eq(NOW));
    PharmacyLogoService.PublicLogo logo = service.readPublicLogo(PID, "png");
    assertThat(logo.contentType()).isEqualTo("image/png");
    assertThat(logo.bytes()).isEqualTo(png);
  }

  @Test
  void uploadJpegReplacesPngAndNormalisesJpegLookup() {
    objects.stored.put("pharmacies/" + PID + "/logo.png", PharmacyKycServiceTest.pngSample());
    byte[] jpeg = PharmacyKycServiceTest.jpegSample();
    Map<String, Object> data =
        service.uploadLogo(
            owner(), jpeg, "board.jpg", "image/jpeg", name -> "https://cdn.example/" + name);

    assertThat(data.get("logo_url")).isEqualTo("https://cdn.example/" + PID + ".jpg");
    assertThat(objects.stored).doesNotContainKey("pharmacies/" + PID + "/logo.png");
    assertThat(service.readPublicLogo(PID, "jpeg").contentType()).isEqualTo("image/jpeg");
    assertThat(service.readPublicLogo(PID, "JPG").bytes()).isEqualTo(jpeg);
  }

  @Test
  void rejectsStaffEmptyOversizeGifVirusAndRateLimit() {
    byte[] png = PharmacyKycServiceTest.pngSample();
    MedmatePrincipal staff =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_STAFF, PID, TokenScope.FULL, "j");
    assertThatThrownBy(
            () -> service.uploadLogo(staff, png, "a.png", "image/png", name -> "https://x/" + name))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(
            () ->
                service.uploadLogo(owner(), png, "a.png", "image/png", name -> "https://x/" + name))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.uploadLogo(
                    owner(), new byte[0], "a.png", "image/png", name -> "https://x/" + name))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_LOGO");
    assertThatThrownBy(
            () ->
                service.uploadLogo(
                    owner(), null, "a.png", "image/png", name -> "https://x/" + name))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_LOGO");

    byte[] tooBig = new byte[PharmacyLogoService.MAX_BYTES + 1];
    tooBig[0] = (byte) 0x89;
    tooBig[1] = 0x50;
    tooBig[2] = 0x4E;
    tooBig[3] = 0x47;
    tooBig[4] = 0x0D;
    tooBig[5] = 0x0A;
    tooBig[6] = 0x1A;
    tooBig[7] = 0x0A;
    assertThatThrownBy(
            () ->
                service.uploadLogo(
                    owner(), tooBig, "a.png", "image/png", name -> "https://x/" + name))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_LOGO");

    assertThatThrownBy(
            () ->
                service.uploadLogo(
                    owner(),
                    new byte[] {1, 2, 3, 4, 5, 6, 7, 8},
                    "a.gif",
                    "image/gif",
                    name -> "https://x/" + name))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_LOGO");

    assertThatThrownBy(
            () ->
                service.uploadLogo(
                    owner(), png, "a.png", "image/jpeg", name -> "https://x/" + name))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_LOGO");

    org.mockito.Mockito.doThrow(new VirusScanner.VirusScanException("bad"))
        .when(virusScanner)
        .scan(any(), any());
    assertThatThrownBy(
            () ->
                service.uploadLogo(owner(), png, "a.png", "image/png", name -> "https://x/" + name))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_LOGO");
    verify(profiles, never()).updateLogoUrl(any(), any(), any());
  }

  @Test
  void resolveImageMimeAllowsBlankClaimedType() {
    assertThat(PharmacyLogoService.resolveImageMime(PharmacyKycServiceTest.pngSample(), null))
        .isEqualTo("image/png");
    assertThat(
            PharmacyLogoService.resolveImageMime(
                PharmacyKycServiceTest.jpegSample(), "image/jpeg; charset=binary"))
        .isEqualTo("image/jpeg");
    assertThat(
            PharmacyLogoService.resolveImageMime(PharmacyKycServiceTest.pngSample(), "image/gif"))
        .isEqualTo("image/png");
    assertThatThrownBy(
            () ->
                PharmacyLogoService.resolveImageMime(
                    PharmacyKycServiceTest.pdfSample(), "application/pdf"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_LOGO");
  }

  @Test
  void publicReadAndParseCoverMissingAndInvalidNames() {
    assertThat(service.readPublicLogo(null, "png")).isNull();
    assertThat(service.readPublicLogo(PID, null)).isNull();
    assertThat(service.readPublicLogo(PID, "gif")).isNull();
    assertThat(service.readPublicLogo(PID, "png")).isNull();
    objects.stored.put("pharmacies/" + PID + "/logo.png", new byte[0]);
    assertThat(service.readPublicLogo(PID, "png")).isNull();
    assertThat(PharmacyLogoService.parsePublicFileName(null)).isNull();
    assertThat(PharmacyLogoService.parsePublicFileName("  ")).isNull();
    assertThat(PharmacyLogoService.parsePublicFileName("../x.png")).isNull();
    assertThat(PharmacyLogoService.parsePublicFileName(PID + ".gif")).isNull();
    PharmacyLogoService.PublicLogoRef parsed =
        PharmacyLogoService.parsePublicFileName(PID + ".JPEG");
    assertThat(parsed.pharmacyId()).isEqualTo(PID);
    assertThat(parsed.ext()).isEqualTo("JPEG");
    assertThat(PharmacyLogoService.publicFileName(PID, "image/jpeg")).isEqualTo(PID + ".jpg");
  }

  private MedmatePrincipal owner() {
    return new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, PID, TokenScope.FULL, "jti");
  }

  static final class MemoryObjects implements KycObjectStore {
    final Map<String, byte[]> stored = new HashMap<>();

    @Override
    public void put(String key, byte[] bytes, String contentType) {
      stored.put(key, bytes);
    }

    @Override
    public byte[] get(String key) {
      return stored.get(key);
    }

    @Override
    public void delete(String key) {
      stored.remove(key);
    }
  }
}

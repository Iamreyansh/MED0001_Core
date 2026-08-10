package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.prescription.application.port.out.CustomerNamePort;
import com.nammamedmate.prescription.application.port.out.OcrJobPort;
import com.nammamedmate.prescription.application.port.out.OcrPort;
import com.nammamedmate.prescription.application.port.out.OrderLinkPort;
import com.nammamedmate.prescription.application.port.out.PrescriptionInUsePort;
import com.nammamedmate.prescription.application.port.out.PrescriptionObjectStore;
import com.nammamedmate.prescription.application.port.out.PrescriptionStore;
import com.nammamedmate.prescription.domain.PrescriptionRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PrescriptionServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T07:30:00Z");
  private static final UUID CUST = UUID.randomUUID();
  private static final MedmatePrincipal CUSTOMER =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @Test
  void listPagingEdges_andFilters_andRateLimit() {
    PrescriptionStore store = mock(PrescriptionStore.class);
    when(store.listForCustomer(any(), any(), any(), anyInt(), anyInt(), any(), any()))
        .thenReturn(new PrescriptionStore.Page(List.of(), 0));
    PrescriptionService service =
        service(store, new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)));

    service.list(CUSTOMER, null, "E_PRESCRIPTION", null, null, null, null);
    service.list(CUSTOMER, " ", "UPLOADED", 0, 0, null, "asc");
    service.list(CUSTOMER, "ALL", null, 1, 500, null, null);

    InMemoryRateLimiter limited = new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC));
    PrescriptionService tight = service(store, limited);
    for (int i = 0; i < 30; i++) {
      tight.list(CUSTOMER, null, null, 1, 20, null, null);
    }
    assertThatThrownBy(() -> tight.list(CUSTOMER, null, null, 1, 20, null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void mime_helpers_andUrlWithoutQuery() {
    assertThat(PrescriptionService.sniffMime(null)).isNull();
    assertThat(PrescriptionService.sniffMime(new byte[] {1, 2})).isNull();
    assertThat(PrescriptionService.sniffMime("%PD".getBytes())).isNull();
    assertThat(PrescriptionService.sniffMime(new byte[] {0x25, 0x00, 0x00, 0x00})).isNull();
    assertThat(PrescriptionService.sniffMime(new byte[] {0x25, 0x50, 0x00, 0x00})).isNull();
    assertThat(PrescriptionService.sniffMime(new byte[] {0x25, 0x50, 0x44, 0x00})).isNull();
    assertThat(PrescriptionService.sniffMime(new byte[] {(byte) 0xFF, (byte) 0xD8})).isNull();
    assertThat(PrescriptionService.sniffMime(new byte[] {(byte) 0xFF, 0x00, (byte) 0xFF})).isNull();
    assertThat(PrescriptionService.sniffMime(new byte[] {(byte) 0xFF, (byte) 0xD8, 0x00})).isNull();
    assertThat(
            PrescriptionService.sniffMime(
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A}))
        .isNull();
    assertThat(
            PrescriptionService.sniffMime(
                new byte[] {(byte) 0x89, 0x00, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}))
        .isNull();
    assertThat(
            PrescriptionService.sniffMime(
                new byte[] {(byte) 0x89, 0x50, 0x00, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}))
        .isNull();
    assertThat(
            PrescriptionService.sniffMime(
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x00, 0x0D, 0x0A, 0x1A, 0x0A}))
        .isNull();
    assertThat(
            PrescriptionService.sniffMime(
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x00, 0x0A, 0x1A, 0x0A}))
        .isNull();
    assertThat(
            PrescriptionService.sniffMime(
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x00, 0x1A, 0x0A}))
        .isNull();
    assertThat(
            PrescriptionService.sniffMime(
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x00, 0x0A}))
        .isNull();
    assertThat(
            PrescriptionService.sniffMime(
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x00}))
        .isNull();
    assertThatThrownBy(() -> PrescriptionService.resolveMimeType(jpeg(), "text/plain"))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> PrescriptionService.resolveMimeType(null, "image/jpeg"))
        .isInstanceOf(AppException.class);
    assertThat(PrescriptionService.resolveMimeType(jpeg(), null)).isEqualTo("image/jpeg");
    assertThat(PrescriptionService.resolveMimeType(jpeg(), "image/jpeg; charset=binary"))
        .isEqualTo("image/jpeg");
    assertThat(PrescriptionService.resolveMimeType(jpeg(), "   ")).isEqualTo("image/jpeg");

    PrescriptionStore store = mock(PrescriptionStore.class);
    UUID id = UUID.randomUUID();
    PrescriptionRecord r =
        new PrescriptionRecord(
            id,
            CUST,
            "UPLOADED",
            "UPLOADED",
            "prescriptions/x.jpg",
            1,
            "image/jpeg",
            null,
            null,
            null,
            null,
            "UPLOAD",
            null,
            null,
            null,
            NOW.plusSeconds(100),
            null,
            NOW,
            NOW,
            null);
    when(store.findByIdForCustomer(id, CUST)).thenReturn(Optional.of(r));
    PresignedUrlService presigner =
        new PresignedUrlService() {
          @Override
          public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
            return new PresignedUrl("https://x/" + key, key, ttl);
          }

          @Override
          public PresignedUrl createGetUrl(String key, Duration ttl) {
            return new PresignedUrl("https://x/" + key, key, ttl);
          }
        };
    PrescriptionService svc =
        new PrescriptionService(
            store,
            mock(PrescriptionObjectStore.class),
            presigner,
            mock(OcrPort.class),
            mock(OcrJobPort.class),
            mock(OrderLinkPort.class),
            mock(PrescriptionInUsePort.class),
            mock(CustomerNamePort.class),
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(svc.get(CUSTOMER, id).get("file_url").toString()).contains("?n=");
  }

  @Test
  void listResult_nullData() {
    PrescriptionService.ListResult result =
        new PrescriptionService.ListResult(
            null, com.nammamedmate.kernel.api.PaginationMeta.of(1, 20, 0));
    assertThat(result.data()).isEmpty();
  }

  @Test
  void nullPrincipal() {
    PrescriptionService service =
        service(
            mock(PrescriptionStore.class),
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)));
    assertThatThrownBy(() -> service.get(null, UUID.randomUUID()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  private static PrescriptionService service(PrescriptionStore store, InMemoryRateLimiter limiter) {
    return new PrescriptionService(
        store,
        mock(PrescriptionObjectStore.class),
        new PresignedUrlService() {
          @Override
          public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
            return new PresignedUrl("https://x/" + key + "?put=1", key, ttl);
          }

          @Override
          public PresignedUrl createGetUrl(String key, Duration ttl) {
            return new PresignedUrl("https://x/" + key + "?get=1", key, ttl);
          }
        },
        mock(OcrPort.class),
        mock(OcrJobPort.class),
        mock(OrderLinkPort.class),
        mock(PrescriptionInUsePort.class),
        mock(CustomerNamePort.class),
        limiter,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static byte[] jpeg() {
    return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
  }
}

package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import com.nammamedmate.prescription.domain.PrescriptionRecord.MedicineExtracted;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PrescriptionServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T07:30:00Z");
  private static final UUID CUST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final MedmatePrincipal CUSTOMER =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @Mock private PrescriptionObjectStore objectStore;
  @Mock private OcrPort ocr;
  @Mock private OrderLinkPort orderLink;
  @Mock private PrescriptionInUsePort inUse;
  @Mock private CustomerNamePort customerNames;

  private FakeStore store;
  private PrescriptionService service;
  private AtomicInteger urlSeq;

  @BeforeEach
  void setUp() {
    store = new FakeStore();
    urlSeq = new AtomicInteger();
    PresignedUrlService presigner =
        new PresignedUrlService() {
          @Override
          public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
            return new PresignedUrl("https://local.invalid/" + key + "?put=1", key, ttl);
          }

          @Override
          public PresignedUrl createGetUrl(String key, Duration ttl) {
            return new PresignedUrl(
                "https://local.invalid/" + key + "?get=" + urlSeq.incrementAndGet(), key, ttl);
          }
        };
    OcrJobPort jobs =
        (id, bytes, mime) -> {
          // sync for tests — call apply after insert
        };
    service =
        new PrescriptionService(
            store,
            objectStore,
            presigner,
            ocr,
            jobs,
            orderLink,
            inUse,
            customerNames,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac_uploadValidJpg_createsUploadedWithSignedUrl() {
    when(customerNames.findName(CUST)).thenReturn(Optional.of("Ravi Kumar"));
    byte[] jpg = jpegBytes();
    Map<String, Object> data = service.upload(CUSTOMER, jpg, "image/jpeg", null, "Morning refill");
    assertThat(data.get("status")).isEqualTo("UPLOADED");
    assertThat(data.get("type")).isEqualTo("UPLOADED");
    assertThat(data.get("patient_name")).isEqualTo("Ravi Kumar");
    assertThat(data.get("file_url").toString()).contains("https://local.invalid/");
    assertThat(data.get("expires_at")).isEqualTo(Instant.parse("2027-01-24T07:30:00Z"));
    assertThat(store.byId).hasSize(1);
    PrescriptionRecord saved = store.byId.values().iterator().next();
    assertThat(saved.s3Key()).startsWith("prescriptions/");
    assertThat(saved.s3Key()).doesNotContain("http");
    verify(objectStore).put(eq(saved.s3Key()), eq(jpg), eq("image/jpeg"));
  }

  @Test
  void ac_fileTooLarge_returns422() {
    byte[] big = new byte[(int) (10L * 1024 * 1024) + 1];
    System.arraycopy(jpegBytes(), 0, big, 0, jpegBytes().length);
    assertThatThrownBy(() -> service.upload(CUSTOMER, big, "image/jpeg", null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FILE_TOO_LARGE");
  }

  @Test
  void ac_docx_invalidFormat() {
    byte[] docx = "PK\u0003\u0004not-an-image".getBytes(StandardCharsets.US_ASCII);
    assertThatThrownBy(
            () ->
                service.upload(
                    CUSTOMER,
                    docx,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    null,
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_FILE_FORMAT");
  }

  @Test
  void ac_deleteInUse_packingOrder_409() {
    PrescriptionRecord r = uploaded();
    store.insert(r);
    when(inUse.isInUse(r.id())).thenReturn(true);
    assertThatThrownBy(() -> service.delete(CUSTOMER, r.id()))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException a = (AppException) ex;
              assertThat(a.code()).isEqualTo("PRESCRIPTION_IN_USE");
              assertThat(a.httpStatus()).isEqualTo(409);
            });
  }

  @Test
  void ac_expiryJob_marksExpired_andBlocksAttach() {
    PrescriptionRecord r =
        new PrescriptionRecord(
            UUID.randomUUID(),
            CUST,
            "UPLOADED",
            "UPLOADED",
            "prescriptions/x.jpg",
            10,
            "image/jpeg",
            "Ravi",
            null,
            null,
            null,
            "UPLOAD",
            null,
            null,
            null,
            NOW.minus(Duration.ofDays(1)),
            null,
            NOW.minus(Duration.ofDays(183)),
            NOW.minus(Duration.ofDays(183)),
            null);
    store.insert(r);
    assertThat(service.expireDue()).isEqualTo(1);
    assertThat(store.byId.get(r.id()).status()).isEqualTo("EXPIRED");
    assertThatThrownBy(() -> service.useInCart(CUSTOMER, r.id(), UUID.randomUUID()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRESCRIPTION_EXPIRED");
  }

  @Test
  void ac_attachExpired_422() {
    PrescriptionRecord r = withExpires(withStatus(uploaded(), "EXPIRED"), NOW.minusSeconds(10));
    store.insert(r);
    assertThatThrownBy(() -> service.useInCart(CUSTOMER, r.id(), UUID.randomUUID()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRESCRIPTION_EXPIRED");
  }

  @Test
  void ac_ocrJob_populatesFields() {
    PrescriptionRecord r = uploaded();
    store.insert(r);
    when(ocr.extract(any(), any()))
        .thenReturn(
            new OcrPort.OcrResult(
                "Dr. Priya Sharma",
                LocalDate.of(2026, 7, 20),
                List.of(new MedicineExtracted("Metformin 500mg", "60 tablets", "1-0-1", "H"))));
    service.applyOcr(r.id(), jpegBytes(), "image/jpeg");
    PrescriptionRecord updated = store.byId.get(r.id());
    assertThat(updated.doctorName()).isEqualTo("Dr. Priya Sharma");
    assertThat(updated.prescriptionDate()).isEqualTo(LocalDate.of(2026, 7, 20));
    assertThat(updated.medicinesExtracted()).hasSize(1);
  }

  @Test
  void applyOcr_upsertsDoctorRegistryWhenWired() {
    PrescriptionRecord r = uploaded();
    store.insert(r);
    DoctorRegistryService registry = mock(DoctorRegistryService.class);
    PrescriptionService wired =
        new PrescriptionService(
            store,
            objectStore,
            new PresignedUrlService() {
              @Override
              public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
                return new PresignedUrl("p", key, ttl);
              }

              @Override
              public PresignedUrl createGetUrl(String key, Duration ttl) {
                return new PresignedUrl("g", key, ttl);
              }
            },
            ocr,
            (id, bytes, mime) -> {},
            orderLink,
            inUse,
            customerNames,
            registry,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(ocr.extract(any(), any()))
        .thenReturn(
            new OcrPort.OcrResult(
                "Dr. Priya", "MH12345", "MBBS MD", "Endo", LocalDate.of(2026, 7, 20), List.of()));
    wired.applyOcr(r.id(), jpegBytes(), "image/jpeg");
    verify(registry).upsertFromOcr(r.id(), "Dr. Priya", "MH12345", "MBBS MD", "Endo");
  }

  @Test
  void ac_getTwice_freshSignedUrlsDiffer() {
    PrescriptionRecord r = uploaded();
    store.insert(r);
    String u1 = service.get(CUSTOMER, r.id()).get("file_url").toString();
    String u2 = service.get(CUSTOMER, r.id()).get("file_url").toString();
    assertThat(u1).isNotEqualTo(u2);
  }

  @Test
  void ac_cannotDeleteEPrescription_403() {
    PrescriptionRecord r =
        new PrescriptionRecord(
            UUID.randomUUID(),
            CUST,
            "E_PRESCRIPTION",
            "VERIFIED",
            "eprescriptions/x.pdf",
            10,
            "application/pdf",
            "Ravi",
            null,
            "Dr. A",
            LocalDate.of(2026, 7, 22),
            "TELECONSULT",
            null,
            null,
            UUID.randomUUID(),
            NOW.plus(Duration.ofDays(90)),
            null,
            NOW,
            NOW,
            null);
    store.insert(r);
    assertThatThrownBy(() -> service.delete(CUSTOMER, r.id()))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException a = (AppException) ex;
              assertThat(a.code()).isEqualTo("CANNOT_DELETE_EPRESCRIPTION");
              assertThat(a.httpStatus()).isEqualTo(403);
            });
  }

  @Test
  void otherCustomer_get_404() {
    PrescriptionRecord r = uploaded();
    store.insert(r);
    MedmatePrincipal other =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.get(other, r.id()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRESCRIPTION_NOT_FOUND");
  }

  @Test
  void ac_cartAttached_otherCustomerViewsPrescription_404Not403() {
    PrescriptionRecord r = withStatus(uploaded(), "VERIFIED");
    store.insert(r);
    UUID cartId = UUID.randomUUID();
    service.useInCart(CUSTOMER, r.id(), cartId);
    verify(orderLink).attachToCart(CUST, cartId, r.id());
    MedmatePrincipal other =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.get(other, r.id()))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException a = (AppException) ex;
              assertThat(a.code()).isEqualTo("PRESCRIPTION_NOT_FOUND");
              assertThat(a.httpStatus()).isEqualTo(404);
            });
  }

  @Test
  void uploadFailed_maps500() {
    doThrow(new RuntimeException("s3 down")).when(objectStore).put(any(), any(), any());
    assertThatThrownBy(() -> service.upload(CUSTOMER, jpegBytes(), "image/jpeg", "X", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UPLOAD_FAILED");
  }

  @Test
  void rejected_cannotAttach() {
    PrescriptionRecord r = withStatus(uploaded(), "REJECTED");
    store.insert(r);
    assertThatThrownBy(() -> service.useInCart(CUSTOMER, r.id(), UUID.randomUUID()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRESCRIPTION_REJECTED");
  }

  @Test
  void useInCart_happy() {
    PrescriptionRecord r = withStatus(uploaded(), "VERIFIED");
    store.insert(r);
    UUID cartId = UUID.randomUUID();
    Map<String, Object> data = service.useInCart(CUSTOMER, r.id(), cartId);
    assertThat(data.get("cart_id")).isEqualTo(cartId);
    assertThat(data.get("prescription_id")).isEqualTo(r.id());
    verify(orderLink).attachToCart(CUST, cartId, r.id());
  }

  @Test
  void delete_happy() {
    PrescriptionRecord r = uploaded();
    store.insert(r);
    when(inUse.isInUse(r.id())).thenReturn(false);
    assertThat(service.delete(CUSTOMER, r.id()).get("message"))
        .isEqualTo("Prescription deleted successfully");
    assertThat(store.byId.get(r.id()).deletedAt()).isNotNull();
  }

  @Test
  void list_paginates() {
    store.insert(uploaded());
    store.insert(uploaded());
    PrescriptionService.ListResult result =
        service.list(CUSTOMER, "ALL", null, 1, 1, "created_at", "desc");
    assertThat(result.data()).hasSize(1);
    assertThat(result.meta().total()).isEqualTo(2);
    assertThat(result.meta().hasNext()).isTrue();
  }

  @Test
  void ocrFailure_ignored() {
    PrescriptionRecord r = uploaded();
    store.insert(r);
    when(ocr.extract(any(), any())).thenThrow(new RuntimeException("ocr down"));
    service.applyOcr(r.id(), jpegBytes(), "image/jpeg");
    assertThat(store.byId.get(r.id()).doctorName()).isNull();
  }

  @Test
  void ocrNull_ignored() {
    PrescriptionRecord r = uploaded();
    store.insert(r);
    when(ocr.extract(any(), any())).thenReturn(null);
    service.applyOcr(r.id(), jpegBytes(), "image/jpeg");
    verify(ocr).extract(any(), any());
    assertThat(store.byId.get(r.id()).doctorName()).isNull();
  }

  @Test
  void mimeMismatch_invalid() {
    assertThatThrownBy(() -> service.upload(CUSTOMER, jpegBytes(), "application/pdf", null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_FILE_FORMAT");
  }

  @Test
  void emptyFile_invalid() {
    assertThatThrownBy(() -> service.upload(CUSTOMER, new byte[0], "image/jpeg", null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_FILE_FORMAT");
  }

  @Test
  void nullFileBytes_invalid() {
    assertThatThrownBy(() -> service.upload(CUSTOMER, null, "image/jpeg", null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_FILE_FORMAT");
  }

  @Test
  void blankPatientName_fallsBackToAccount() {
    when(customerNames.findName(CUST)).thenReturn(Optional.of("Account Name"));
    Map<String, Object> data = service.upload(CUSTOMER, jpegBytes(), "image/jpeg", "   ", null);
    assertThat(data.get("patient_name")).isEqualTo("Account Name");
  }

  @Test
  void notesTooLong_validation() {
    assertThatThrownBy(
            () -> service.upload(CUSTOMER, jpegBytes(), "image/jpeg", "A", "x".repeat(501)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void patientNameTooLong_validation() {
    assertThatThrownBy(
            () -> service.upload(CUSTOMER, jpegBytes(), "image/jpeg", "x".repeat(201), null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void invalidTypeFilter() {
    assertThatThrownBy(() -> service.list(CUSTOMER, null, "NOPE", 1, 20, null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void useInCart_requiresCartId() {
    store.insert(uploaded());
    UUID id = store.byId.keySet().iterator().next();
    assertThatThrownBy(() -> service.useInCart(CUSTOMER, id, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void unauthorized_nonCustomer() {
    MedmatePrincipal admin =
        new MedmatePrincipal(CUST, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(admin, null, null, 1, 20, null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void detail_includesMedicines() {
    PrescriptionRecord r =
        withOrder(
            withMeds(uploaded(), List.of(new MedicineExtracted("X", "1", "1-0-0", null))),
            UUID.randomUUID());
    store.insert(r);
    Map<String, Object> detail = service.get(CUSTOMER, r.id());
    assertThat(detail.get("medicines_extracted")).isInstanceOf(List.class);
    assertThat((List<?>) detail.get("associated_orders")).hasSize(1);
  }

  @Test
  void pdfAndPng_accepted() {
    service.upload(CUSTOMER, pdfBytes(), "application/pdf", "P", null);
    service.upload(CUSTOMER, pngBytes(), "image/png", "P", " ");
    assertThat(store.byId).hasSize(2);
  }

  @Test
  void imageJpgAlias_accepted() {
    service.upload(CUSTOMER, jpegBytes(), "image/jpg", "P", null);
    verify(objectStore).put(anyString(), any(), eq("image/jpeg"));
  }

  @Test
  void expireDue_delegates() {
    assertThat(service.expireDue()).isZero();
  }

  private PrescriptionRecord uploaded() {
    UUID id = UUID.randomUUID();
    return copy(
        id,
        CUST,
        "UPLOADED",
        "UPLOADED",
        "prescriptions/" + id + ".jpg",
        100,
        "image/jpeg",
        "Ravi",
        null,
        null,
        null,
        "UPLOAD",
        null,
        null,
        null,
        NOW.plus(Duration.ofDays(180)),
        null,
        NOW,
        NOW,
        null);
  }

  private static PrescriptionRecord copy(
      UUID id,
      UUID customerId,
      String type,
      String status,
      String s3Key,
      long fileSizeBytes,
      String mimeType,
      String patientName,
      String notes,
      String doctorName,
      LocalDate prescriptionDate,
      String source,
      List<MedicineExtracted> medicinesExtracted,
      UUID associatedOrderId,
      UUID teleconsultId,
      Instant expiresAt,
      String rejectionReason,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt) {
    return new PrescriptionRecord(
        id,
        customerId,
        type,
        status,
        s3Key,
        fileSizeBytes,
        mimeType,
        patientName,
        notes,
        doctorName,
        prescriptionDate,
        source,
        medicinesExtracted,
        associatedOrderId,
        teleconsultId,
        expiresAt,
        rejectionReason,
        createdAt,
        updatedAt,
        deletedAt);
  }

  private static PrescriptionRecord withStatus(PrescriptionRecord r, String status) {
    return copy(
        r.id(),
        r.customerId(),
        r.type(),
        status,
        r.s3Key(),
        r.fileSizeBytes(),
        r.mimeType(),
        r.patientName(),
        r.notes(),
        r.doctorName(),
        r.prescriptionDate(),
        r.source(),
        r.medicinesExtracted(),
        r.associatedOrderId(),
        r.teleconsultId(),
        r.expiresAt(),
        r.rejectionReason(),
        r.createdAt(),
        r.updatedAt(),
        r.deletedAt());
  }

  private static PrescriptionRecord withExpires(PrescriptionRecord r, Instant expiresAt) {
    return copy(
        r.id(),
        r.customerId(),
        r.type(),
        r.status(),
        r.s3Key(),
        r.fileSizeBytes(),
        r.mimeType(),
        r.patientName(),
        r.notes(),
        r.doctorName(),
        r.prescriptionDate(),
        r.source(),
        r.medicinesExtracted(),
        r.associatedOrderId(),
        r.teleconsultId(),
        expiresAt,
        r.rejectionReason(),
        r.createdAt(),
        r.updatedAt(),
        r.deletedAt());
  }

  private static PrescriptionRecord withMeds(PrescriptionRecord r, List<MedicineExtracted> meds) {
    return copy(
        r.id(),
        r.customerId(),
        r.type(),
        r.status(),
        r.s3Key(),
        r.fileSizeBytes(),
        r.mimeType(),
        r.patientName(),
        r.notes(),
        r.doctorName(),
        r.prescriptionDate(),
        r.source(),
        meds,
        r.associatedOrderId(),
        r.teleconsultId(),
        r.expiresAt(),
        r.rejectionReason(),
        r.createdAt(),
        r.updatedAt(),
        r.deletedAt());
  }

  private static PrescriptionRecord withOrder(PrescriptionRecord r, UUID orderId) {
    return copy(
        r.id(),
        r.customerId(),
        r.type(),
        r.status(),
        r.s3Key(),
        r.fileSizeBytes(),
        r.mimeType(),
        r.patientName(),
        r.notes(),
        r.doctorName(),
        r.prescriptionDate(),
        r.source(),
        r.medicinesExtracted(),
        orderId,
        r.teleconsultId(),
        r.expiresAt(),
        r.rejectionReason(),
        r.createdAt(),
        r.updatedAt(),
        r.deletedAt());
  }

  private static byte[] jpegBytes() {
    return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03};
  }

  private static byte[] pdfBytes() {
    byte[] b = new byte[8];
    System.arraycopy("%PDF".getBytes(StandardCharsets.US_ASCII), 0, b, 0, 4);
    return b;
  }

  private static byte[] pngBytes() {
    return new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
  }

  private static final class FakeStore implements PrescriptionStore {
    final Map<UUID, PrescriptionRecord> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(PrescriptionRecord record) {
      byId.put(record.id(), record);
    }

    @Override
    public Optional<PrescriptionRecord> findByIdForCustomer(UUID id, UUID customerId) {
      return Optional.ofNullable(byId.get(id))
          .filter(r -> r.customerId().equals(customerId) && r.deletedAt() == null);
    }

    @Override
    public Optional<PrescriptionRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id)).filter(r -> r.deletedAt() == null);
    }

    @Override
    public Page listForCustomer(
        UUID customerId,
        String status,
        String type,
        int page,
        int limit,
        String sort,
        String order) {
      List<PrescriptionRecord> all =
          byId.values().stream()
              .filter(r -> r.customerId().equals(customerId) && r.deletedAt() == null)
              .filter(r -> status == null || status.equals(r.status()))
              .filter(r -> type == null || type.equals(r.type()))
              .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
              .toList();
      int from = Math.min((page - 1) * limit, all.size());
      int to = Math.min(from + limit, all.size());
      return new Page(new ArrayList<>(all.subList(from, to)), all.size());
    }

    @Override
    public void softDelete(UUID id, Instant deletedAt, Instant updatedAt) {
      PrescriptionRecord r = byId.get(id);
      if (r == null) {
        return;
      }
      byId.put(
          id,
          new PrescriptionRecord(
              r.id(),
              r.customerId(),
              r.type(),
              r.status(),
              r.s3Key(),
              r.fileSizeBytes(),
              r.mimeType(),
              r.patientName(),
              r.notes(),
              r.doctorName(),
              r.prescriptionDate(),
              r.source(),
              r.medicinesExtracted(),
              r.associatedOrderId(),
              r.teleconsultId(),
              r.expiresAt(),
              r.rejectionReason(),
              r.createdAt(),
              updatedAt,
              deletedAt));
    }

    @Override
    public void updateOcr(
        UUID id,
        String doctorName,
        LocalDate prescriptionDate,
        List<MedicineExtracted> medicines,
        Instant updatedAt) {
      PrescriptionRecord r = byId.get(id);
      if (r == null) {
        return;
      }
      byId.put(
          id,
          new PrescriptionRecord(
              r.id(),
              r.customerId(),
              r.type(),
              r.status(),
              r.s3Key(),
              r.fileSizeBytes(),
              r.mimeType(),
              r.patientName(),
              r.notes(),
              doctorName,
              prescriptionDate,
              r.source(),
              medicines,
              r.associatedOrderId(),
              r.teleconsultId(),
              r.expiresAt(),
              r.rejectionReason(),
              r.createdAt(),
              updatedAt,
              r.deletedAt()));
    }

    @Override
    public void updateStatus(UUID id, String status, Instant updatedAt) {
      PrescriptionRecord r = byId.get(id);
      if (r == null) {
        return;
      }
      byId.put(
          id,
          new PrescriptionRecord(
              r.id(),
              r.customerId(),
              r.type(),
              status,
              r.s3Key(),
              r.fileSizeBytes(),
              r.mimeType(),
              r.patientName(),
              r.notes(),
              r.doctorName(),
              r.prescriptionDate(),
              r.source(),
              r.medicinesExtracted(),
              r.associatedOrderId(),
              r.teleconsultId(),
              r.expiresAt(),
              r.rejectionReason(),
              r.createdAt(),
              updatedAt,
              r.deletedAt()));
    }

    @Override
    public int markExpiredDue(Instant now, Instant updatedAt) {
      int n = 0;
      for (PrescriptionRecord r : List.copyOf(byId.values())) {
        if (r.deletedAt() != null) {
          continue;
        }
        if ("EXPIRED".equals(r.status()) || "DISPENSED".equals(r.status())) {
          continue;
        }
        if (!r.expiresAt().isAfter(now)) {
          byId.put(
              r.id(),
              new PrescriptionRecord(
                  r.id(),
                  r.customerId(),
                  r.type(),
                  "EXPIRED",
                  r.s3Key(),
                  r.fileSizeBytes(),
                  r.mimeType(),
                  r.patientName(),
                  r.notes(),
                  r.doctorName(),
                  r.prescriptionDate(),
                  r.source(),
                  r.medicinesExtracted(),
                  r.associatedOrderId(),
                  r.teleconsultId(),
                  r.expiresAt(),
                  r.rejectionReason(),
                  r.createdAt(),
                  updatedAt,
                  r.deletedAt()));
          n++;
        }
      }
      return n;
    }
  }
}

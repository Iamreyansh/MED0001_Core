package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.rider.adapter.out.client.StubAadhaarKycAdapter;
import com.nammamedmate.rider.application.port.out.RiderKycDocumentStore;
import com.nammamedmate.rider.application.port.out.RiderKycDocumentStore.DocumentRecord;
import com.nammamedmate.rider.application.port.out.RiderObjectStore;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.ListFilter;
import com.nammamedmate.rider.application.port.out.RiderStore.PageResult;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiderKycServiceTest {

  private static final UUID RIDER_ID = Ids.newId();
  private FakeRiderStore riders;
  private FakeDocs docs;
  private FakeObjects objects;
  private RiderKycService service;
  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    riders = new FakeRiderStore();
    docs = new FakeDocs();
    objects = new FakeObjects();
    riders.insert(sampleRider("PENDING_KYC", "NOT_SUBMITTED"));
    service =
        new RiderKycService(
            riders, docs, objects, new FakePresign(), new StubAadhaarKycAdapter(), clock, false);
  }

  @Test
  void ac004_submitWithoutLicence() {
    assertThatThrownBy(() -> service.submitKyc(riderPrincipal()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DRIVING_LICENCE_MISSING");
  }

  @Test
  void ac005_uploadExpiredInsurance() {
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    riderPrincipal(),
                    "VEHICLE_INSURANCE",
                    pdfSample(),
                    "application/pdf",
                    "2020-01-01",
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOCUMENT_EXPIRED");
  }

  @Test
  void uploadAndSubmitHappyPath() {
    Map<String, Object> uploaded =
        service.uploadDocument(
            riderPrincipal(), "DRIVING_LICENCE", pdfSample(), "application/pdf", null, "DL1");
    assertThat(uploaded.get("file_url").toString()).contains("kyc/riders/");
    service.uploadDocument(
        riderPrincipal(), "VEHICLE_INSURANCE", pdfSample(), "application/pdf", "2029-12-31", null);
    Map<String, Object> data = service.submitKyc(riderPrincipal());
    assertThat(data.get("kyc_status")).isEqualTo("SUBMITTED");
    assertThat(riders.findById(RIDER_ID).orElseThrow().kycStatus()).isEqualTo("SUBMITTED");
  }

  @Test
  void ac007_resubmitAfterReject() {
    service.uploadDocument(
        riderPrincipal(), "DRIVING_LICENCE", pdfSample(), "application/pdf", null, "DL1");
    service.submitKyc(riderPrincipal());
    riders.update(
        new RiderRecord(
            RIDER_ID,
            "Ravi",
            "+919999900010",
            null,
            "BIKE",
            "KA01AB1234",
            null,
            "PENDING_KYC",
            "REJECTED",
            clock.instant(),
            clock.instant(),
            Ids.newId(),
            "DOCUMENT_UNCLEAR",
            "blurry licence",
            false,
            null,
            0,
            null,
            0L,
            0L,
            0,
            null,
            null,
            null,
            clock.instant(),
            clock.instant()));
    assertThat(riders.findById(RIDER_ID).orElseThrow().kycRejectionReason())
        .isEqualTo("DOCUMENT_UNCLEAR");
    service.uploadDocument(
        riderPrincipal(), "DRIVING_LICENCE", pdfSample(), "application/pdf", null, "DL2");
    Map<String, Object> data = service.submitKyc(riderPrincipal());
    assertThat(data.get("kyc_status")).isEqualTo("SUBMITTED");
    assertThat(riders.findById(RIDER_ID).orElseThrow().kycRejectionReason()).isNull();
  }

  @Test
  void uploadOverwritesAndEnforcesLimit() {
    for (int i = 0; i < 5; i++) {
      service.uploadDocument(riderPrincipal(), "PAN", pdfSample(), "application/pdf", null, null);
    }
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    riderPrincipal(), "PAN", pdfSample(), "application/pdf", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UPLOAD_LIMIT_REACHED");
  }

  @Test
  void unsupportedFormatAndTooLarge() {
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    riderPrincipal(), "PAN", new byte[] {1, 2, 3}, "application/pdf", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNSUPPORTED_FILE_FORMAT");
    byte[] huge = new byte[(int) RiderKycService.MAX_FILE_BYTES + 1];
    System.arraycopy(pdfSample(), 0, huge, 0, pdfSample().length);
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    riderPrincipal(), "PAN", huge, "application/pdf", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FILE_TOO_LARGE");
  }

  @Test
  void invalidDocumentType() {
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    riderPrincipal(), "X", pdfSample(), "application/pdf", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DOCUMENT_TYPE");
  }

  @Test
  void alreadySubmitted() {
    riders.update(sampleRider("PENDING_KYC", "SUBMITTED"));
    assertThatThrownBy(() -> service.submitKyc(riderPrincipal()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("KYC_ALREADY_SUBMITTED");
  }

  @Test
  void submitExpiredInsuranceOnSubmit() {
    service.uploadDocument(
        riderPrincipal(), "DRIVING_LICENCE", pdfSample(), "application/pdf", null, null);
    docs.insert(
        new DocumentRecord(
            Ids.newId(),
            RIDER_ID,
            "VEHICLE_INSURANCE",
            null,
            "k",
            "u",
            10,
            "application/pdf",
            LocalDate.parse("2026-07-24"),
            false,
            "PENDING",
            null,
            clock.instant(),
            null,
            null));
    assertThatThrownBy(() -> service.submitKyc(riderPrincipal()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOCUMENT_EXPIRED_ON_SUBMIT");
  }

  @Test
  void aadhaarFlagSetsVerified() {
    RiderKycService withFlag =
        new RiderKycService(
            riders, docs, objects, new FakePresign(), new StubAadhaarKycAdapter(), clock, true);
    withFlag.uploadDocument(
        riderPrincipal(), "DRIVING_LICENCE", pdfSample(), "application/pdf", null, null);
    withFlag.uploadDocument(
        riderPrincipal(), "AADHAAR", pdfSample(), "application/pdf", null, "1234");
    withFlag.submitKyc(riderPrincipal());
    assertThat(riders.findById(RIDER_ID).orElseThrow().aadhaarVerified()).isTrue();
  }

  @Test
  void sniffMimeAndExtension() {
    assertThat(RiderKycService.sniffMime(pdfSample())).isEqualTo("application/pdf");
    assertThat(RiderKycService.sniffMime(jpegSample())).isEqualTo("image/jpeg");
    assertThat(RiderKycService.sniffMime(pngSample())).isEqualTo("image/png");
    assertThat(RiderKycService.sniffMime(null)).isNull();
    assertThat(RiderKycService.extensionFor("application/pdf")).isEqualTo("pdf");
    assertThat(RiderKycService.extensionFor("image/jpeg")).isEqualTo("jpg");
    assertThat(RiderKycService.extensionFor("image/png")).isEqualTo("png");
    assertThat(RiderKycService.extensionFor("x")).isEqualTo("bin");
  }

  @Test
  void authGuards() {
    assertThatThrownBy(
            () -> service.uploadDocument(null, "PAN", pdfSample(), "application/pdf", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    riders.update(sampleRider("BLOCKED", "NOT_SUBMITTED"));
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    riderPrincipal(), "PAN", pdfSample(), "application/pdf", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.submitKyc(riderPrincipal()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  private MedmatePrincipal riderPrincipal() {
    return new MedmatePrincipal(RIDER_ID, AuthRole.RIDER, null, TokenScope.FULL, "j");
  }

  private RiderRecord sampleRider(String status, String kyc) {
    Instant now = clock.instant();
    return new RiderRecord(
        RIDER_ID,
        "Ravi",
        "+919876543210",
        null,
        "BIKE",
        "KA01AB1234",
        null,
        status,
        kyc,
        null,
        null,
        null,
        null,
        null,
        false,
        null,
        0,
        null,
        0L,
        0L,
        0,
        null,
        null,
        null,
        now,
        now);
  }

  static byte[] pdfSample() {
    return "%PDF-1.4 sample".getBytes(StandardCharsets.US_ASCII);
  }

  static byte[] jpegSample() {
    return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0};
  }

  static byte[] pngSample() {
    return new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
  }

  static final class FakePresign implements PresignedUrlService {
    @Override
    public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
      return new PresignedUrl("https://local/" + key, key, ttl);
    }

    @Override
    public PresignedUrl createGetUrl(String key, Duration ttl) {
      return new PresignedUrl("https://local/" + key + "?get=1", key, ttl);
    }
  }

  static final class FakeObjects implements RiderObjectStore {
    final Map<String, byte[]> stored = new HashMap<>();

    @Override
    public void put(String key, byte[] bytes, String contentType) {
      stored.put(key, bytes);
    }

    @Override
    public void delete(String key) {
      stored.remove(key);
    }
  }

  static final class FakeDocs implements RiderKycDocumentStore {
    final List<DocumentRecord> history = new CopyOnWriteArrayList<>();
    final List<UUID> deleted = new CopyOnWriteArrayList<>();

    @Override
    public void insert(DocumentRecord doc) {
      history.add(doc);
    }

    @Override
    public void softDelete(UUID id, Instant deletedAt) {
      deleted.add(id);
    }

    @Override
    public Optional<DocumentRecord> findActiveByRiderAndType(UUID riderId, String documentType) {
      return history.stream()
          .filter(d -> d.riderId().equals(riderId) && d.documentType().equals(documentType))
          .filter(d -> !deleted.contains(d.id()))
          .reduce((a, b) -> b);
    }

    @Override
    public List<DocumentRecord> findActiveByRider(UUID riderId) {
      return history.stream()
          .filter(d -> d.riderId().equals(riderId))
          .filter(d -> !deleted.contains(d.id()))
          .toList();
    }

    @Override
    public int countUploadsByRiderAndType(UUID riderId, String documentType) {
      return (int)
          history.stream()
              .filter(d -> d.riderId().equals(riderId) && d.documentType().equals(documentType))
              .count();
    }

    @Override
    public List<DocumentRecord> findDueForExpiryAlert(LocalDate onOrBefore, LocalDate after) {
      return List.of();
    }

    @Override
    public void markExpiryAlertSent(UUID documentId) {}
  }

  static final class FakeRiderStore implements RiderStore {
    final Map<UUID, RiderRecord> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(RiderRecord rider) {
      byId.put(rider.id(), rider);
    }

    @Override
    public Optional<RiderRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<RiderRecord> findByPhone(String phone) {
      return byId.values().stream().filter(r -> r.phone().equals(phone)).findFirst();
    }

    @Override
    public boolean existsByPhone(String phone) {
      return findByPhone(phone).isPresent();
    }

    @Override
    public void update(RiderRecord rider) {
      byId.put(rider.id(), rider);
    }

    @Override
    public PageResult list(ListFilter filter) {
      return new PageResult(new ArrayList<>(byId.values()), byId.size());
    }

    @Override
    public void updateAvailability(UUID id, String status, UUID currentZoneId, Instant updatedAt) {}

    @Override
    public void updatePrimaryZone(UUID id, UUID primaryZoneId, Instant updatedAt) {}
  }
}

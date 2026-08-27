package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.kernel.storage.PresignedUrlService.PresignedUrl;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.application.port.out.KycDocumentStore;
import com.nammamedmate.pharmacy.application.port.out.KycDocumentStore.KycAccessAuditRecord;
import com.nammamedmate.pharmacy.application.port.out.KycDocumentStore.KycDocumentRecord;
import com.nammamedmate.pharmacy.application.port.out.KycDocumentStore.KycExpiryAlertRecord;
import com.nammamedmate.pharmacy.application.port.out.KycObjectStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore.PharmacyRecord;
import com.nammamedmate.pharmacy.application.port.out.VirusScanner;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PharmacyKycServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");
  private static final UUID PHARMACY_ID = Ids.newId();
  private static final UUID OWNER_ID = Ids.newId();
  private static final UUID ADMIN_ID = Ids.newId();

  private FakePharmacyStore pharmacyStore;
  private FakeKycDocStore kycStore;
  private FakeObjectStore objectStore;
  private FakeVirusScanner virusScanner;
  private RateLimiter rateLimiter;
  private FakePresignedUrls presignedUrls;
  private OutboxPublisher outbox;
  private InMemoryOutboxStore outboxStore;
  private Clock clock;
  private PharmacyKycService service;

  static byte[] pdfSample() {
    return "%PDF-1.4 sample".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  }

  static byte[] pdfSample(int minLen) {
    byte[] out = new byte[Math.max(minLen, 16)];
    byte[] magic = "%PDF-1.4".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    System.arraycopy(magic, 0, out, 0, magic.length);
    return out;
  }

  static byte[] jpegSample() {
    return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
  }

  static byte[] pngSample() {
    return new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
  }

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(NOW, ZoneOffset.UTC);
    pharmacyStore = new FakePharmacyStore();
    kycStore = new FakeKycDocStore();
    objectStore = new FakeObjectStore();
    virusScanner = new FakeVirusScanner();
    rateLimiter = new AllowAllRateLimiter();
    presignedUrls = new FakePresignedUrls();
    outboxStore = new InMemoryOutboxStore();
    outbox = new OutboxPublisher(outboxStore, new ObjectMapper());
    service =
        new PharmacyKycService(
            pharmacyStore,
            kycStore,
            objectStore,
            virusScanner,
            presignedUrls,
            outbox,
            rateLimiter,
            clock);

    pharmacyStore.save(pendingKycPharmacy(PHARMACY_ID));
  }

  // ─── AC-001: Upload happy path ────────────────────────────────────────────────

  @Test
  void uploadDrugLicencePdfWithExpiry() {
    byte[] bytes = pdfSample();
    Map<String, Object> data =
        service.uploadDocument(
            ownerPrincipal(),
            "DRUG_LICENCE",
            bytes,
            "drug-licence.pdf",
            "application/pdf",
            "2027-06-30");
    assertThat(data.get("status")).isEqualTo("UPLOADED");
    assertThat(data.get("document_type")).isEqualTo("DRUG_LICENCE");
    assertThat(data.get("file_size_bytes")).isEqualTo((long) bytes.length);
    assertThat(data.get("expiry_date")).isEqualTo("2027-06-30");
    assertThat(data.get("signed_url")).isNull();
    assertThat(data.get("signed_url_expires_at")).isNull();
    assertThat(data.get("scan_status")).isEqualTo("PENDING");
    assertThat(kycStore.docs).hasSize(1);
    assertThat(objectStore.stored).hasSize(1);
    // Expiry alerts scheduled for T-60 and T-30
    assertThat(kycStore.expiryAlerts).hasSize(2);
  }

  @Test
  void uploadJpegWithoutExpiryForGstin() {
    Map<String, Object> data =
        service.uploadDocument(
            ownerPrincipal(), "GSTIN_CERTIFICATE", jpegSample(), "gstin.jpg", "image/jpeg", null);
    assertThat(data.get("status")).isEqualTo("UPLOADED");
    assertThat(data.get("expiry_date")).isNull();
    assertThat(kycStore.expiryAlerts).isEmpty();
  }

  // ─── AC-002: File too large ───────────────────────────────────────────────────

  @Test
  void uploadRejectsFileTooLarge() {
    byte[] huge = pdfSample((int) PharmacyKycService.MAX_FILE_BYTES + 1);
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(), "PAN_CARD", huge, "file.pdf", "application/pdf", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FILE_TOO_LARGE");
    assertThat(objectStore.stored).isEmpty();
  }

  @Test
  void uploadRejectsEmptyFile() {
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(), "PAN_CARD", new byte[0], "file.pdf", "application/pdf", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_FILE_TYPE");
  }

  // ─── Invalid file type ───────────────────────────────────────────────────────

  @Test
  void uploadRejectsUnsupportedMime() {
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(),
                    "PAN_CARD",
                    pdfSample(10),
                    "file.exe",
                    "application/octet-stream",
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_FILE_TYPE");
  }

  @Test
  void uploadRejectsMismatchedMagicBytes() {
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(),
                    "PAN_CARD",
                    "not-a-pdf".getBytes(),
                    "file.pdf",
                    "application/pdf",
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_FILE_TYPE");
  }

  @Test
  void uploadRateLimited() {
    com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter limiter =
        new com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter(clock);
    PharmacyKycService limited =
        new PharmacyKycService(
            pharmacyStore,
            kycStore,
            objectStore,
            virusScanner,
            presignedUrls,
            outbox,
            limiter,
            clock);
    for (int i = 0; i < PharmacyKycService.UPLOAD_LIMIT; i++) {
      limited.uploadDocument(
          ownerPrincipal(), "PAN_CARD", pdfSample(), "p.pdf", "application/pdf", null);
      kycStore.docs.clear();
      objectStore.stored.clear();
    }
    assertThatThrownBy(
            () ->
                limited.uploadDocument(
                    ownerPrincipal(), "PAN_CARD", pdfSample(), "p.pdf", "application/pdf", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void sniffMimeDetectsPdfJpegPng() {
    assertThat(PharmacyKycService.sniffMime(pdfSample())).isEqualTo("application/pdf");
    assertThat(PharmacyKycService.sniffMime(jpegSample())).isEqualTo("image/jpeg");
    assertThat(PharmacyKycService.sniffMime(pngSample())).isEqualTo("image/png");
    assertThat(PharmacyKycService.sniffMime(new byte[] {1, 2})).isNull();
    assertThat(PharmacyKycService.sniffMime(new byte[] {1, 2, 3, 4})).isNull();
    assertThat(PharmacyKycService.sniffMime(new byte[] {1, 2, 3, 4, 5, 6, 7, 8})).isNull();
    assertThat(PharmacyKycService.sniffMime(null)).isNull();
    // length gates: short arrays skip PDF/PNG checks
    assertThat(PharmacyKycService.sniffMime(new byte[] {0x25, 0x50, 0x44})).isNull();
    assertThat(
            PharmacyKycService.sniffMime(
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A}))
        .isNull();
    // JPEG partial mismatches
    assertThat(PharmacyKycService.sniffMime(new byte[] {(byte) 0xFF, 0x00, (byte) 0xFF})).isNull();
    assertThat(PharmacyKycService.sniffMime(new byte[] {(byte) 0xFF, (byte) 0xD8, 0x00})).isNull();
    assertThat(PharmacyKycService.sniffMime(new byte[] {0x00, (byte) 0xD8, (byte) 0xFF})).isNull();
  }

  @Test
  void uploadRejectsClaimedMimeNotMatchingSniff() {
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(), "PAN_CARD", pdfSample(), "file.jpg", "image/jpeg", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_FILE_TYPE");
  }

  // ─── Invalid document type ───────────────────────────────────────────────────

  @Test
  void uploadRejectsInvalidDocumentType() {
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(),
                    "UNKNOWN_TYPE",
                    pdfSample(10),
                    "x.pdf",
                    "application/pdf",
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DOCUMENT_TYPE");
  }

  @Test
  void uploadRejectsNullDocumentType() {
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(), null, pdfSample(10), "x.pdf", "application/pdf", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DOCUMENT_TYPE");
  }

  // ─── Expiry date validation ──────────────────────────────────────────────────

  @Test
  void uploadDrugLicenceWithoutExpiryFails() {
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(),
                    "DRUG_LICENCE",
                    pdfSample(10),
                    "dl.pdf",
                    "application/pdf",
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EXPIRY_DATE_REQUIRED");
  }

  @Test
  void uploadFssaiWithoutExpiryFails() {
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(),
                    "FSSAI_CERTIFICATE",
                    pdfSample(10),
                    "fssai.pdf",
                    "application/pdf",
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EXPIRY_DATE_REQUIRED");
  }

  @Test
  void uploadWithPastExpiryFails() {
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(),
                    "DRUG_LICENCE",
                    pdfSample(10),
                    "dl.pdf",
                    "application/pdf",
                    "2020-01-01"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EXPIRY_DATE_IN_PAST");
  }

  @Test
  void uploadWithInvalidExpiryFormat() {
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(),
                    "DRUG_LICENCE",
                    pdfSample(10),
                    "dl.pdf",
                    "application/pdf",
                    "not-a-date"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EXPIRY_DATE_REQUIRED");
  }

  // ─── AC-003: Duplicate pending document ──────────────────────────────────────

  @Test
  void uploadRejectsDuplicatePendingDocument() {
    // Insert an existing UPLOADED GSTIN doc
    kycStore.docs.add(docRecord(PHARMACY_ID, "GSTIN_CERTIFICATE", "UPLOADED"));

    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(),
                    "GSTIN_CERTIFICATE",
                    pdfSample(10),
                    "x.pdf",
                    "application/pdf",
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOCUMENT_TYPE_ALREADY_PENDING");
  }

  @Test
  void uploadAllowsAfterRejectedDoc() {
    // REJECTED docs don't block — they can be re-uploaded
    kycStore.docs.add(docRecord(PHARMACY_ID, "GSTIN_CERTIFICATE", "REJECTED"));
    assertThat(
            service.uploadDocument(
                ownerPrincipal(), "GSTIN_CERTIFICATE", pngSample(), "x.png", "image/png", null))
        .containsKey("document_id");
  }

  // ─── ACTIVE pharmacy blocks upload ────────────────────────────────────────────

  @Test
  void uploadBlockedForActivePharmacy() {
    pharmacyStore.save(pharmacyWithStatus(PHARMACY_ID, "ACTIVE"));
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(),
                    "GSTIN_CERTIFICATE",
                    pdfSample(10),
                    "x.pdf",
                    "application/pdf",
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_ALREADY_ACTIVE");
  }

  // ─── Virus scan ──────────────────────────────────────────────────────────────

  @Test
  void uploadRejectsInfectedFile() {
    virusScanner.rejectNext = true;
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(),
                    "GSTIN_CERTIFICATE",
                    pdfSample(10),
                    "x.pdf",
                    "application/pdf",
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FILE_SCAN_FAILED");
    assertThat(objectStore.stored).isEmpty();
  }

  // ─── Auth checks for upload ──────────────────────────────────────────────────

  @Test
  void uploadRequiresOwnerRole() {
    MedmatePrincipal staff =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_STAFF, PHARMACY_ID, TokenScope.FULL, "j");
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    staff, "GSTIN_CERTIFICATE", pdfSample(10), "x.pdf", "application/pdf", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void uploadRequiresNonNullPrincipal() {
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    null, "GSTIN_CERTIFICATE", pdfSample(10), "x.pdf", "application/pdf", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  // ─── List documents ──────────────────────────────────────────────────────────

  @Test
  void listDocumentsForOwner() {
    kycStore.docs.add(docRecord(PHARMACY_ID, "DRUG_LICENCE", "UPLOADED"));
    kycStore.docs.add(docRecord(PHARMACY_ID, "GSTIN_CERTIFICATE", "VERIFIED"));
    Map<String, Object> data = service.listDocuments(ownerPrincipal());
    assertThat(data.get("pharmacy_id")).isEqualTo(PHARMACY_ID.toString());
    assertThat(data.get("kyc_status")).isEqualTo("PENDING_KYC");
    @SuppressWarnings("unchecked")
    List<?> docs = (List<?>) data.get("documents");
    assertThat(docs).hasSize(2);
    assertThat(data.get("ready_to_submit")).isEqualTo(false);
  }

  @Test
  void listDocumentsForStaff() {
    MedmatePrincipal staff =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_STAFF, PHARMACY_ID, TokenScope.FULL, "j");
    Map<String, Object> data = service.listDocuments(staff);
    assertThat(data).containsKey("pharmacy_id");
  }

  @Test
  void listDocumentsRequiresPharmacyRole() {
    MedmatePrincipal customer =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.listDocuments(customer))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  // ─── AC-004: Submit happy path ───────────────────────────────────────────────

  @Test
  void submitKycRejectsUnscannedDocuments() {
    kycStore.docs.add(docRecord(PHARMACY_ID, "GSTIN_CERTIFICATE", "UPLOADED"));
    kycStore.docs.add(docRecord(PHARMACY_ID, "DRUG_LICENCE", "UPLOADED"));
    kycStore.docs.add(docRecord(PHARMACY_ID, "FSSAI_CERTIFICATE", "UPLOADED"));
    kycStore.docs.add(docRecord(PHARMACY_ID, "PAN_CARD", "UPLOADED"));
    kycStore.docs.add(docRecord(PHARMACY_ID, "BANK_STATEMENT", "UPLOADED"));
    assertThatThrownBy(() -> service.submitKyc(ownerPrincipal()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOCUMENTS_QUARANTINED");
  }

  @Test
  void submitKycWithAllRequiredDocuments() {
    addAllRequiredDocs();
    Map<String, Object> data = service.submitKyc(ownerPrincipal());
    assertThat(data.get("status")).isEqualTo("KYC_SUBMITTED");
    assertThat(data.get("auto_kyc_triggered")).isEqualTo(false);
    assertThat(data.get("estimated_review_hours")).isEqualTo(24);
    assertThat(pharmacyStore.lastUpdatedStatus).isEqualTo("KYC_SUBMITTED");
    // All UPLOADED → UNDER_REVIEW
    assertThat(kycStore.setUnderReviewCalledFor).contains(PHARMACY_ID);
    // Outbox event published
    assertThat(outboxStore.all()).isNotEmpty();
  }

  // ─── Submit: error paths ─────────────────────────────────────────────────────

  @Test
  void submitRequiresEmailVerified() {
    pharmacyStore.save(emailUnverifiedPharmacy(PHARMACY_ID));
    assertThatThrownBy(() -> service.submitKyc(ownerPrincipal()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMAIL_NOT_VERIFIED");
  }

  @Test
  void submitFailsWhenAlreadyActive() {
    pharmacyStore.save(pharmacyWithStatus(PHARMACY_ID, "ACTIVE"));
    assertThatThrownBy(() -> service.submitKyc(ownerPrincipal()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALREADY_ACTIVE");
  }

  @Test
  void submitFailsWhenAlreadySubmitted() {
    pharmacyStore.save(pharmacyWithStatus(PHARMACY_ID, "KYC_SUBMITTED"));
    assertThatThrownBy(() -> service.submitKyc(ownerPrincipal()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALREADY_SUBMITTED");
  }

  @Test
  void submitFailsWhenRejectedAndCannotReapply() {
    pharmacyStore.save(rejectedPharmacyNoReapply(PHARMACY_ID));
    assertThatThrownBy(() -> service.submitKyc(ownerPrincipal()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CANNOT_REAPPLY");
  }

  @Test
  void submitFailsWhenDocumentsMissing() {
    // Only 2 out of 5 required docs
    kycStore.docs.add(docRecord(PHARMACY_ID, "GSTIN_CERTIFICATE", "UPLOADED"));
    kycStore.docs.add(docRecord(PHARMACY_ID, "DRUG_LICENCE", "UPLOADED"));
    assertThatThrownBy(() -> service.submitKyc(ownerPrincipal()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOCUMENTS_INCOMPLETE");
  }

  @Test
  void submitAllowsReapplyWhenKycRejectedAndReapplyEnabled() {
    pharmacyStore.save(rejectedPharmacyCanReapply(PHARMACY_ID));
    addAllRequiredDocs();
    Map<String, Object> data = service.submitKyc(ownerPrincipal());
    assertThat(data.get("status")).isEqualTo("KYC_SUBMITTED");
  }

  // ─── AC-005: Delete happy path ────────────────────────────────────────────────

  @Test
  void deleteRejectedDocument() {
    UUID docId = Ids.newId();
    kycStore.docs.add(docRecordWithId(docId, PHARMACY_ID, "PAN_CARD", "REJECTED"));
    Map<String, Object> data = service.deleteDocument(ownerPrincipal(), docId);
    assertThat(data.get("deleted")).isEqualTo(true);
    assertThat(kycStore.deletedDocs).contains(docId);
  }

  @Test
  void deleteUploadedDocument() {
    UUID docId = Ids.newId();
    kycStore.docs.add(docRecordWithId(docId, PHARMACY_ID, "PAN_CARD", "UPLOADED"));
    Map<String, Object> data = service.deleteDocument(ownerPrincipal(), docId);
    assertThat(data.get("deleted")).isEqualTo(true);
  }

  // ─── AC-006: Delete verified fails ───────────────────────────────────────────

  @Test
  void deleteVerifiedDocumentForbidden() {
    UUID docId = Ids.newId();
    kycStore.docs.add(docRecordWithId(docId, PHARMACY_ID, "GSTIN_CERTIFICATE", "VERIFIED"));
    assertThatThrownBy(() -> service.deleteDocument(ownerPrincipal(), docId))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CANNOT_DELETE_VERIFIED");
  }

  @Test
  void deleteUnderReviewDocumentForbidden() {
    UUID docId = Ids.newId();
    kycStore.docs.add(docRecordWithId(docId, PHARMACY_ID, "GSTIN_CERTIFICATE", "UNDER_REVIEW"));
    assertThatThrownBy(() -> service.deleteDocument(ownerPrincipal(), docId))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CANNOT_DELETE_UNDER_REVIEW");
  }

  @Test
  void deleteNotFoundReturns404() {
    assertThatThrownBy(() -> service.deleteDocument(ownerPrincipal(), Ids.newId()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOCUMENT_NOT_FOUND");
  }

  // ─── Admin GET KYC ─────────────────────────────────────────────────────────

  @Test
  void adminGetKycReturnsDocumentsWithAuditLog() {
    UUID docId = Ids.newId();
    kycStore.docs.add(docRecordWithId(docId, PHARMACY_ID, "GSTIN_CERTIFICATE", "UNDER_REVIEW"));
    Map<String, Object> data = service.adminGetKyc(adminPrincipal(), PHARMACY_ID);
    assertThat(data.get("pharmacy_id")).isEqualTo(PHARMACY_ID.toString());
    assertThat(data.get("auto_kyc_result")).isNull();
    @SuppressWarnings("unchecked")
    List<?> docs = (List<?>) data.get("documents");
    assertThat(docs).hasSize(1);
    // Audit log inserted
    assertThat(kycStore.auditRecords).hasSize(1);
    assertThat(kycStore.auditRecords.get(0).adminId()).isEqualTo(ADMIN_ID);
    assertThat(kycStore.auditRecords.get(0).documentId()).isEqualTo(docId);
    assertThat(kycStore.auditRecords.get(0).accessedAt()).isEqualTo(NOW);
  }

  @Test
  void adminGetKycReturns404ForMissingPharmacy() {
    assertThatThrownBy(() -> service.adminGetKyc(adminPrincipal(), Ids.newId()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
  }

  @Test
  void adminGetKycForbiddenForNonAdmin() {
    assertThatThrownBy(() -> service.adminGetKyc(ownerPrincipal(), PHARMACY_ID))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  // ─── AC-007: Admin verify/reject ─────────────────────────────────────────────

  @Test
  void adminVerifyDocumentSetsVerified() {
    UUID docId = Ids.newId();
    kycStore.docs.add(docRecordWithId(docId, PHARMACY_ID, "PAN_CARD", "UNDER_REVIEW"));
    Map<String, Object> data =
        service.adminVerifyDocument(adminPrincipal(), PHARMACY_ID, docId, true, null);
    assertThat(data.get("status")).isEqualTo("VERIFIED");
    assertThat(data.get("verified_by")).isEqualTo(ADMIN_ID.toString());
    assertThat(data.get("rejection_reason")).isNull();
  }

  @Test
  void adminRejectDocumentWithReason() {
    UUID docId = Ids.newId();
    kycStore.docs.add(docRecordWithId(docId, PHARMACY_ID, "PAN_CARD", "UNDER_REVIEW"));
    Map<String, Object> data =
        service.adminVerifyDocument(adminPrincipal(), PHARMACY_ID, docId, false, "Image is blurry");
    assertThat(data.get("status")).isEqualTo("REJECTED");
    assertThat(data.get("rejection_reason")).isEqualTo("Image is blurry");
    assertThat(data.get("verified_by")).isEqualTo(ADMIN_ID.toString());
    assertThat(data.get("verified_at")).isEqualTo(NOW.toString());
  }

  @Test
  void adminRejectWithoutReasonFails() {
    UUID docId = Ids.newId();
    kycStore.docs.add(docRecordWithId(docId, PHARMACY_ID, "PAN_CARD", "UNDER_REVIEW"));
    assertThatThrownBy(
            () -> service.adminVerifyDocument(adminPrincipal(), PHARMACY_ID, docId, false, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REJECTION_REASON_REQUIRED");
  }

  @Test
  void adminRejectWithBlankReasonFails() {
    UUID docId = Ids.newId();
    kycStore.docs.add(docRecordWithId(docId, PHARMACY_ID, "PAN_CARD", "UNDER_REVIEW"));
    assertThatThrownBy(
            () -> service.adminVerifyDocument(adminPrincipal(), PHARMACY_ID, docId, false, "  "))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REJECTION_REASON_REQUIRED");
  }

  @Test
  void adminRejectWithTooLongReasonFails() {
    UUID docId = Ids.newId();
    kycStore.docs.add(docRecordWithId(docId, PHARMACY_ID, "PAN_CARD", "UNDER_REVIEW"));
    assertThatThrownBy(
            () ->
                service.adminVerifyDocument(
                    adminPrincipal(), PHARMACY_ID, docId, false, "A".repeat(501)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REJECTION_REASON_REQUIRED");
  }

  @Test
  void adminVerifyAlreadyVerifiedFails() {
    UUID docId = Ids.newId();
    kycStore.docs.add(docRecordWithId(docId, PHARMACY_ID, "PAN_CARD", "VERIFIED"));
    assertThatThrownBy(
            () -> service.adminVerifyDocument(adminPrincipal(), PHARMACY_ID, docId, true, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOCUMENT_ALREADY_VERIFIED");
  }

  @Test
  void adminVerifyDocumentNotFound() {
    assertThatThrownBy(
            () ->
                service.adminVerifyDocument(adminPrincipal(), PHARMACY_ID, Ids.newId(), true, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOCUMENT_NOT_FOUND");
  }

  // ─── Business rules: required documents ──────────────────────────────────────

  @Test
  void fssaiRequiredForPharmacy() {
    List<String> required = PharmacyKycService.requiredDocumentTypes("PHARMACY");
    assertThat(required).contains("FSSAI_CERTIFICATE");
  }

  @Test
  void fssaiRequiredForHospital() {
    List<String> required = PharmacyKycService.requiredDocumentTypes("HOSPITAL");
    assertThat(required).contains("FSSAI_CERTIFICATE");
  }

  @Test
  void fssaiNotRequiredForClinicPharmacy() {
    List<String> required = PharmacyKycService.requiredDocumentTypes("CLINIC_PHARMACY");
    assertThat(required).doesNotContain("FSSAI_CERTIFICATE");
  }

  @Test
  void proprietorIdSatisfiesIdentityRequirement() {
    List<KycDocumentRecord> docs = new ArrayList<>();
    docs.add(docRecord(PHARMACY_ID, "GSTIN_CERTIFICATE", "UPLOADED"));
    docs.add(docRecord(PHARMACY_ID, "DRUG_LICENCE", "UPLOADED"));
    docs.add(docRecord(PHARMACY_ID, "FSSAI_CERTIFICATE", "UPLOADED"));
    docs.add(docRecord(PHARMACY_ID, "PAN_CARD", "UPLOADED"));
    docs.add(docRecord(PHARMACY_ID, "PROPRIETOR_ID", "UPLOADED")); // instead of BANK_STATEMENT
    List<String> required = PharmacyKycService.requiredDocumentTypes("PHARMACY");
    List<String> missing = PharmacyKycService.computeMissing(docs, required);
    assertThat(missing).isEmpty();
  }

  @Test
  void rejectedDocCountsAsMissing() {
    List<KycDocumentRecord> docs = new ArrayList<>();
    docs.add(docRecord(PHARMACY_ID, "GSTIN_CERTIFICATE", "REJECTED"));
    List<String> required = List.of("GSTIN_CERTIFICATE");
    List<String> missing = PharmacyKycService.computeMissing(docs, required);
    assertThat(missing).contains("GSTIN_CERTIFICATE");
  }

  // ─── Count helpers ───────────────────────────────────────────────────────────

  @Test
  void countActiveDocuments() {
    kycStore.docs.add(docRecord(PHARMACY_ID, "GSTIN_CERTIFICATE", "UPLOADED"));
    kycStore.docs.add(docRecord(PHARMACY_ID, "DRUG_LICENCE", "VERIFIED"));
    kycStore.docs.add(docRecord(PHARMACY_ID, "PAN_CARD", "REJECTED"));
    assertThat(service.countActiveDocuments(PHARMACY_ID)).isEqualTo(2);
    assertThat(service.countVerifiedDocuments(PHARMACY_ID)).isEqualTo(1);
    assertThat(service.countRejectedDocuments(PHARMACY_ID)).isEqualTo(1);
  }

  // ─── Expiry alert dedup ──────────────────────────────────────────────────────

  @Test
  void expiryAlertNotDuplicatedWhenAlertDateInPast() {
    // Drug licence expiry tomorrow — only T-30 alert future, T-60 would be in past
    LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
    byte[] bytes = pdfSample();
    service.uploadDocument(
        ownerPrincipal(), "DRUG_LICENCE", bytes, "dl.pdf", "application/pdf", tomorrow.toString());
    // T-60 would be 59 days in the past → skipped. T-30 would be 29 days in the past → skipped.
    // Both past → no alerts
    assertThat(kycStore.expiryAlerts).isEmpty();
  }

  // ─── Mime content type with charset ─────────────────────────────────────────

  @Test
  void uploadWithCharsetInContentType() {
    byte[] bytes = pdfSample();
    Map<String, Object> data =
        service.uploadDocument(
            ownerPrincipal(), "PAN_CARD", bytes, "pan.pdf", "application/pdf; charset=utf-8", null);
    assertThat(data.get("status")).isEqualTo("UPLOADED");
  }

  // ─── FSSAI upload with expiry ────────────────────────────────────────────────

  @Test
  void uploadFssaiWithExpiry() {
    byte[] bytes = pdfSample();
    Map<String, Object> data =
        service.uploadDocument(
            ownerPrincipal(),
            "FSSAI_CERTIFICATE",
            bytes,
            "fssai.pdf",
            "application/pdf",
            "2027-12-31");
    assertThat(data.get("document_type")).isEqualTo("FSSAI_CERTIFICATE");
    assertThat(kycStore.expiryAlerts).hasSize(2);
    // Template names
    assertThat(kycStore.expiryAlerts.stream().map(KycExpiryAlertRecord::template))
        .containsExactlyInAnyOrder("FSSAI_EXPIRY_REMINDER_60", "FSSAI_EXPIRY_REMINDER_30");
  }

  // ─── Admin role checks ──────────────────────────────────────────────────────

  @Test
  void adminGetKycForbiddenForCustomer() {
    MedmatePrincipal cust =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.adminGetKyc(cust, PHARMACY_ID))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void adminVerifyForbiddenForNullPrincipal() {
    assertThatThrownBy(
            () -> service.adminVerifyDocument(null, PHARMACY_ID, Ids.newId(), true, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  // ─── Principals ─────────────────────────────────────────────────────────────

  private MedmatePrincipal ownerPrincipal() {
    return new MedmatePrincipal(
        OWNER_ID, AuthRole.PHARMACY_OWNER, PHARMACY_ID, TokenScope.FULL, "jti");
  }

  private MedmatePrincipal adminPrincipal() {
    return new MedmatePrincipal(ADMIN_ID, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "jti");
  }

  private void addAllRequiredDocs() {
    kycStore.docs.add(docRecord(PHARMACY_ID, "GSTIN_CERTIFICATE", "SCAN_CLEAN"));
    kycStore.docs.add(docRecord(PHARMACY_ID, "DRUG_LICENCE", "SCAN_CLEAN"));
    kycStore.docs.add(docRecord(PHARMACY_ID, "FSSAI_CERTIFICATE", "SCAN_CLEAN"));
    kycStore.docs.add(docRecord(PHARMACY_ID, "PAN_CARD", "SCAN_CLEAN"));
    kycStore.docs.add(docRecord(PHARMACY_ID, "BANK_STATEMENT", "SCAN_CLEAN"));
  }

  // ─── Fake implementations ────────────────────────────────────────────────────

  static KycDocumentRecord docRecord(UUID pharmacyId, String type, String status) {
    return docRecordWithId(Ids.newId(), pharmacyId, type, status);
  }

  static KycDocumentRecord docRecordWithId(UUID id, UUID pharmacyId, String type, String status) {
    return new KycDocumentRecord(
        id,
        pharmacyId,
        type,
        "kyc/" + pharmacyId + "/" + type + "/" + id + ".pdf",
        "doc.pdf",
        1024L,
        "application/pdf",
        status,
        null,
        null,
        null,
        null,
        NOW,
        NOW);
  }

  static PharmacyRecord pendingKycPharmacy(UUID id) {
    return new PharmacyRecord(
        id,
        "Sharma Medical",
        "Sharma Medical",
        "Priya Sharma",
        "+919876543210",
        "owner@test.in",
        "hash",
        "PHARMACY",
        Map.of(),
        "PENDING_KYC",
        "FREE",
        null,
        "29AABPP1234F1ZZ",
        "DL-001",
        "29",
        "12345678901234",
        "AABPP1234F",
        new BigDecimal("8.00"),
        null,
        false,
        true,
        true,
        "Bengaluru",
        "FREE",
        NOW,
        NOW,
        null);
  }

  static PharmacyRecord emailUnverifiedPharmacy(UUID id) {
    return new PharmacyRecord(
        id,
        "Shop",
        "Shop",
        "Owner",
        "+919876543211",
        "owner2@test.in",
        "hash",
        "PHARMACY",
        Map.of(),
        "PENDING_KYC",
        "FREE",
        null,
        "g",
        "d",
        "29",
        null,
        "p",
        new BigDecimal("8.00"),
        null,
        false,
        false,
        true,
        "C",
        "FREE",
        NOW,
        NOW,
        null);
  }

  static PharmacyRecord pharmacyWithStatus(UUID id, String status) {
    return new PharmacyRecord(
        id,
        "Shop",
        "Shop",
        "Owner",
        "+919876543212",
        "o3@test.in",
        "hash",
        "PHARMACY",
        Map.of(),
        status,
        "FREE",
        null,
        "g2",
        "d2",
        "29",
        null,
        "p2",
        new BigDecimal("8.00"),
        null,
        false,
        true,
        true,
        "C",
        "FREE",
        NOW,
        NOW,
        null);
  }

  static PharmacyRecord rejectedPharmacyNoReapply(UUID id) {
    return new PharmacyRecord(
        id,
        "Shop",
        "Shop",
        "Owner",
        "+919876543213",
        "o4@test.in",
        "hash",
        "PHARMACY",
        Map.of(),
        "REJECTED",
        "FREE",
        null,
        "g3",
        "d3",
        "29",
        null,
        "p3",
        new BigDecimal("8.00"),
        null,
        false,
        true,
        false,
        "C",
        "FREE",
        NOW,
        NOW,
        null);
  }

  static PharmacyRecord rejectedPharmacyCanReapply(UUID id) {
    return new PharmacyRecord(
        id,
        "Shop",
        "Shop",
        "Owner",
        "+919876543214",
        "o5@test.in",
        "hash",
        "PHARMACY",
        Map.of(),
        "KYC_REJECTED",
        "FREE",
        null,
        "g4",
        "d4",
        "29",
        null,
        "p4",
        new BigDecimal("8.00"),
        null,
        false,
        true,
        true,
        "C",
        "FREE",
        NOW,
        NOW,
        null);
  }

  static final class AllowAllRateLimiter implements RateLimiter {
    @Override
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
      return true;
    }

    @Override
    public int secondsUntilAvailable(String key, int limit, int windowSeconds) {
      return 0;
    }

    @Override
    public void putCooldown(String key, int ttlSeconds) {}

    @Override
    public int cooldownRemainingSeconds(String key) {
      return 0;
    }
  }

  static final class FakePharmacyStore implements PharmacyRegistrationStore {
    private final Map<UUID, PharmacyRecord> store = new HashMap<>();
    String lastUpdatedStatus;

    void save(PharmacyRecord r) {
      store.put(r.id(), r);
    }

    @Override
    public void insert(PharmacyRecord pharmacy) {
      store.put(pharmacy.id(), pharmacy);
    }

    @Override
    public Optional<PharmacyRecord> findById(UUID id) {
      return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<PharmacyRecord> findByEmail(String email) {
      return Optional.empty();
    }

    @Override
    public boolean existsGstin(String gstin) {
      return false;
    }

    @Override
    public boolean existsPan(String pan) {
      return false;
    }

    @Override
    public boolean existsDrugLicence(String licence, String stateCode) {
      return false;
    }

    @Override
    public boolean existsPhone(String phone) {
      return false;
    }

    @Override
    public boolean existsEmail(String email) {
      return false;
    }

    @Override
    public void markEmailVerified(UUID pharmacyId, Instant at) {}

    @Override
    public void updateStatus(
        UUID pharmacyId, String status, Instant kycSubmittedAt, Instant updatedAt) {
      lastUpdatedStatus = status;
      PharmacyRecord p = store.get(pharmacyId);
      if (p != null) {
        store.put(
            pharmacyId,
            new PharmacyRecord(
                p.id(),
                p.name(),
                p.businessName(),
                p.ownerName(),
                p.phone(),
                p.email(),
                p.passwordHash(),
                p.businessType(),
                p.address(),
                status,
                p.plan(),
                p.planExpiresAt(),
                p.gstin(),
                p.drugLicenceNumber(),
                p.licenceStateCode(),
                p.fssaiNumber(),
                p.panNumber(),
                p.commissionPct(),
                p.zoneId(),
                p.online(),
                p.emailVerified(),
                p.canReapply(),
                p.city(),
                p.subscriptionPlan(),
                p.createdAt(),
                updatedAt,
                kycSubmittedAt));
      }
    }

    @Override
    public void activateAfterAutoKyc(UUID pharmacyId, UUID zoneId, Instant at) {
      updateStatus(pharmacyId, "ACTIVE", null, at);
    }
  }

  static class FakeKycDocStore implements KycDocumentStore {
    final List<KycDocumentRecord> docs = new ArrayList<>();
    final List<UUID> deletedDocs = new ArrayList<>();
    final List<UUID> setUnderReviewCalledFor = new ArrayList<>();
    final List<KycAccessAuditRecord> auditRecords = new ArrayList<>();
    final List<KycExpiryAlertRecord> expiryAlerts = new ArrayList<>();

    @Override
    public void insert(KycDocumentRecord doc) {
      docs.add(doc);
    }

    @Override
    public Optional<KycDocumentRecord> findById(UUID docId, UUID pharmacyId) {
      return docs.stream()
          .filter(d -> d.id().equals(docId) && d.pharmacyId().equals(pharmacyId))
          .findFirst();
    }

    @Override
    public Optional<KycDocumentRecord> findByFileKey(String fileKey) {
      return docs.stream().filter(d -> d.fileKey().equals(fileKey)).findFirst();
    }

    @Override
    public List<KycDocumentRecord> findActiveByPharmacy(UUID pharmacyId) {
      return docs.stream().filter(d -> d.pharmacyId().equals(pharmacyId)).toList();
    }

    @Override
    public void updateStatus(
        UUID docId,
        String status,
        String rejectionReason,
        UUID verifiedBy,
        Instant verifiedAt,
        Instant updatedAt) {
      docs.replaceAll(
          d ->
              d.id().equals(docId)
                  ? new KycDocumentRecord(
                      d.id(),
                      d.pharmacyId(),
                      d.documentType(),
                      d.fileKey(),
                      d.fileName(),
                      d.fileSizeBytes(),
                      d.fileMimeType(),
                      status,
                      rejectionReason,
                      d.expiryDate(),
                      verifiedBy,
                      verifiedAt,
                      d.createdAt(),
                      updatedAt)
                  : d);
    }

    @Override
    public void softDelete(UUID docId, Instant deletedAt) {
      deletedDocs.add(docId);
    }

    @Override
    public void setAllUploadedToUnderReview(UUID pharmacyId, Instant updatedAt) {
      setUnderReviewCalledFor.add(pharmacyId);
    }

    @Override
    public int countByPharmacyAndStatuses(UUID pharmacyId, List<String> statuses) {
      return (int)
          docs.stream()
              .filter(d -> d.pharmacyId().equals(pharmacyId) && statuses.contains(d.status()))
              .count();
    }

    @Override
    public void insertAccessAudit(KycAccessAuditRecord record) {
      auditRecords.add(record);
    }

    @Override
    public void insertExpiryAlert(KycExpiryAlertRecord record) {
      expiryAlerts.add(record);
    }

    @Override
    public boolean existsExpiryAlert(UUID documentId, String template) {
      return false;
    }
  }

  static final class FakeObjectStore implements KycObjectStore {
    final List<String> stored = new ArrayList<>();
    final List<String> deleted = new ArrayList<>();

    @Override
    public void put(String key, byte[] bytes, String contentType) {
      stored.add(key);
    }

    @Override
    public byte[] get(String key) {
      return stored.contains(key) ? new byte[] {1} : null;
    }

    @Override
    public void delete(String key) {
      deleted.add(key);
      stored.remove(key);
    }
  }

  static final class FakeVirusScanner implements VirusScanner {
    boolean rejectNext;

    @Override
    public void scan(byte[] content, String fileName) {
      if (rejectNext) {
        rejectNext = false;
        throw new VirusScanException("Test virus detected");
      }
    }
  }

  static final class FakePresignedUrls implements PresignedUrlService {
    @Override
    public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
      return new PresignedUrl("https://local.invalid/" + key + "?put=1", key, ttl);
    }

    @Override
    public PresignedUrl createGetUrl(String key, Duration ttl) {
      return new PresignedUrl("https://local.invalid/" + key + "?get=1", key, ttl);
    }
  }

  // ─── Additional branch-coverage tests ────────────────────────────────────────

  @Test
  void uploadOwnerWithNullPharmacyIdUnauthorized() {
    MedmatePrincipal p =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    p, "PAN_CARD", pdfSample(10), "x.pdf", "application/pdf", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void uploadPharmacyNotFoundUnauthorized() {
    UUID otherId = Ids.newId();
    MedmatePrincipal p =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, otherId, TokenScope.FULL, "j");
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    p, "PAN_CARD", pdfSample(10), "x.pdf", "application/pdf", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void listWithNullPrincipalUnauthorized() {
    assertThatThrownBy(() -> service.listDocuments(null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void listWithNonPharmacyPrincipalHavingPharmacyIdForbidden() {
    MedmatePrincipal p =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, PHARMACY_ID, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.listDocuments(p))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void adminGetKycWithNullPrincipalUnauthorized() {
    assertThatThrownBy(() -> service.adminGetKyc(null, PHARMACY_ID))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void uploadBlankDocumentTypeReturnsInvalidType() {
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(), "   ", pdfSample(10), "x.pdf", "application/pdf", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DOCUMENT_TYPE");
  }

  @Test
  void requiredDocumentTypesWithNullBusinessTypeIncludesFssai() {
    List<String> required = PharmacyKycService.requiredDocumentTypes(null);
    assertThat(required).contains("FSSAI_CERTIFICATE");
  }

  @Test
  void extensionForDefaultCaseBin() {
    assertThat(PharmacyKycService.extensionFor("application/octet-stream")).isEqualTo("bin");
  }

  @Test
  void expiryAlertSkippedWhenAlreadyExists() {
    FakeKycDocStore storeWithAlerts = new FakeKycDocStoreWithExistingAlerts();
    PharmacyKycService svc =
        new PharmacyKycService(
            pharmacyStore,
            storeWithAlerts,
            objectStore,
            virusScanner,
            presignedUrls,
            outbox,
            rateLimiter,
            clock);
    // Upload DRUG_LICENCE far in future — both T-60 and T-30 would be future, but existsAlert=true
    svc.uploadDocument(
        ownerPrincipal(),
        "DRUG_LICENCE",
        pdfSample(),
        "dl.pdf",
        "application/pdf",
        LocalDate.of(2028, 12, 31).toString());
    // existsAlert returns true → no new alerts inserted
    assertThat(storeWithAlerts.expiryAlerts).isEmpty();
  }

  @Test
  void adminGetKycWithNullBusinessNameAndNonNullSubmittedAtAndVerifiedDoc() {
    pharmacyStore.save(pharmacyNullBusinessNameWithSubmittedAt(PHARMACY_ID));
    UUID docId = Ids.newId();
    kycStore.docs.add(verifiedDoc(docId, PHARMACY_ID, "PAN_CARD"));
    Map<String, Object> data = service.adminGetKyc(adminPrincipal(), PHARMACY_ID);
    assertThat(data.get("business_name")).isNotNull();
    assertThat(data.get("submitted_at")).isNotNull();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> docs = (List<Map<String, Object>>) data.get("documents");
    assertThat(docs.get(0).get("verified_by")).isNotNull();
    assertThat(docs.get(0).get("verified_at")).isNotNull();
  }

  @Test
  void listDocumentsShowsSubmittedAtWhenSet() {
    pharmacyStore.save(pharmacyWithSubmittedAt(PHARMACY_ID, NOW.minusSeconds(3600)));
    Map<String, Object> data = service.listDocuments(ownerPrincipal());
    assertThat(data.get("submitted_at")).isNotNull();
  }

  @Test
  void listDocumentsShowsExpiryDate() {
    kycStore.docs.add(
        docWithExpiry(PHARMACY_ID, "DRUG_LICENCE", "UPLOADED", LocalDate.of(2027, 12, 31)));
    Map<String, Object> data = service.listDocuments(ownerPrincipal());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> docs = (List<Map<String, Object>>) data.get("documents");
    assertThat(docs.get(0).get("expiry_date")).isEqualTo("2027-12-31");
  }

  @Test
  void adminVerifyDocumentPharmacyNotFound() {
    assertThatThrownBy(
            () ->
                service.adminVerifyDocument(adminPrincipal(), Ids.newId(), Ids.newId(), true, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
  }

  // ─── Extra fakes / helpers ───────────────────────────────────────────────────

  static KycDocumentRecord verifiedDoc(UUID id, UUID pharmacyId, String type) {
    return new KycDocumentRecord(
        id,
        pharmacyId,
        type,
        "kyc/" + pharmacyId + "/" + type + "/" + id + ".pdf",
        "doc.pdf",
        1024L,
        "application/pdf",
        "VERIFIED",
        null,
        null,
        Ids.newId(),
        NOW.minusSeconds(100),
        NOW,
        NOW);
  }

  static KycDocumentRecord docWithExpiry(
      UUID pharmacyId, String type, String status, LocalDate expiry) {
    UUID id = Ids.newId();
    return new KycDocumentRecord(
        id,
        pharmacyId,
        type,
        "kyc/" + pharmacyId + "/" + type + "/" + id + ".pdf",
        "doc.pdf",
        1024L,
        "application/pdf",
        status,
        null,
        expiry,
        null,
        null,
        NOW,
        NOW);
  }

  static PharmacyRecord pharmacyNullBusinessNameWithSubmittedAt(UUID id) {
    return new PharmacyRecord(
        id,
        "Shop Name",
        null,
        "Owner",
        "+919876543215",
        "o6@test.in",
        "hash",
        "PHARMACY",
        Map.of(),
        "KYC_SUBMITTED",
        "FREE",
        null,
        "g5",
        "d5",
        "29",
        null,
        "p5",
        new java.math.BigDecimal("8.00"),
        null,
        false,
        true,
        true,
        "C",
        "FREE",
        NOW,
        NOW,
        NOW.minusSeconds(3600));
  }

  static PharmacyRecord pharmacyWithSubmittedAt(UUID id, Instant submittedAt) {
    return new PharmacyRecord(
        id,
        "Shop",
        "Shop",
        "Owner",
        "+919876543216",
        "o7@test.in",
        "hash",
        "PHARMACY",
        Map.of(),
        "KYC_SUBMITTED",
        "FREE",
        null,
        "g6",
        "d6",
        "29",
        null,
        "p6",
        new java.math.BigDecimal("8.00"),
        null,
        false,
        true,
        true,
        "C",
        "FREE",
        NOW,
        NOW,
        submittedAt);
  }

  /** FakeKycDocStore variant where existsExpiryAlert always returns true. */
  static final class FakeKycDocStoreWithExistingAlerts extends FakeKycDocStore {
    @Override
    public boolean existsExpiryAlert(UUID documentId, String template) {
      return true;
    }
  }

  // ─── uploadDocument branch coverage ─────────────────────────────────────────

  @Test
  void uploadNullContentTypeUsesEmptyMime() {
    // null contentType → mimeType = "" → not allowed → INVALID_FILE_TYPE
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(), "PAN_CARD", pdfSample(10), "x.pdf", null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_FILE_TYPE");
  }

  @Test
  void uploadNullFileBytesReturnsInvalidFileType() {
    // null fileBytes → INVALID_FILE_TYPE (empty/null check)
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(), "PAN_CARD", null, "x.pdf", "application/pdf", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_FILE_TYPE");
  }

  @Test
  void uploadWithBlankExpiryDateReturnsExpiryRequired() {
    // blank expiry date string for a type requiring expiry
    assertThatThrownBy(
            () ->
                service.uploadDocument(
                    ownerPrincipal(),
                    "DRUG_LICENCE",
                    pdfSample(10),
                    "dl.pdf",
                    "application/pdf",
                    "   "))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EXPIRY_DATE_REQUIRED");
  }

  @Test
  void uploadNullOriginalFilenameUsesDefaultName() {
    // null originalFileName → uses "document.pdf"
    Map<String, Object> data =
        service.uploadDocument(
            ownerPrincipal(), "PAN_CARD", pdfSample(), null, "application/pdf", null);
    assertThat(data.get("status")).isEqualTo("UPLOADED");
  }

  @Test
  void uploadSucceedsWhenExistingDocOfDifferentTypePresent() {
    // Existing DRUG_LICENCE UPLOADED, uploading GSTIN → loop sees different type → continues
    kycStore.docs.add(docRecord(PHARMACY_ID, "DRUG_LICENCE", "UPLOADED"));
    Map<String, Object> data =
        service.uploadDocument(
            ownerPrincipal(), "GSTIN_CERTIFICATE", pdfSample(), "g.pdf", "application/pdf", null);
    assertThat(data.get("status")).isEqualTo("UPLOADED");
  }

  // ─── requireAdminRole: ADMIN_SUPER and ADMIN_COMPLIANCE paths ────────────────

  @Test
  void adminGetKycWithAdminSuperPrincipalSucceeds() {
    MedmatePrincipal superAdmin =
        new MedmatePrincipal(ADMIN_ID, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    Map<String, Object> data = service.adminGetKyc(superAdmin, PHARMACY_ID);
    assertThat(data.get("pharmacy_id")).isEqualTo(PHARMACY_ID.toString());
  }

  @Test
  void adminGetKycWithAdminCompliancePrincipalSucceeds() {
    MedmatePrincipal complianceAdmin =
        new MedmatePrincipal(ADMIN_ID, AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    Map<String, Object> data = service.adminGetKyc(complianceAdmin, PHARMACY_ID);
    assertThat(data.get("pharmacy_id")).isEqualTo(PHARMACY_ID.toString());
  }

  @Test
  void listDocumentsNotReadyWhenRequiredDocStillUnscanned() {
    addAllRequiredDocs();
    kycStore.docs.removeIf(d -> "PAN_CARD".equals(d.documentType()));
    kycStore.docs.add(docRecord(PHARMACY_ID, "PAN_CARD", "UPLOADED"));
    Map<String, Object> data = service.listDocuments(ownerPrincipal());
    assertThat(data.get("ready_to_submit")).isEqualTo(false);
  }

  @Test
  void listDocumentsReadyWhenScanCleanAndIgnoresRejected() {
    addAllRequiredDocs();
    kycStore.docs.add(docRecord(PHARMACY_ID, "PROPRIETOR_ID", "REJECTED"));
    Map<String, Object> data = service.listDocuments(ownerPrincipal());
    assertThat(data.get("ready_to_submit")).isEqualTo(true);
  }

  @Test
  void submitKycIgnoresRejectedExtraDocument() {
    addAllRequiredDocs();
    kycStore.docs.add(docRecord(PHARMACY_ID, "PROPRIETOR_ID", "REJECTED"));
    Map<String, Object> data = service.submitKyc(ownerPrincipal());
    assertThat(data.get("status")).isEqualTo("KYC_SUBMITTED");
  }

  @Test
  void adminGetKycHidesUnscannedDocuments() {
    UUID uploadedId = Ids.newId();
    kycStore.docs.add(docRecordWithId(uploadedId, PHARMACY_ID, "GSTIN_CERTIFICATE", "UPLOADED"));
    kycStore.docs.add(
        new KycDocumentRecord(
            Ids.newId(),
            PHARMACY_ID,
            "PAN_CARD",
            "kyc/pan.pdf",
            "pan.pdf",
            1024L,
            "application/pdf",
            "UPLOADED",
            null,
            null,
            ADMIN_ID,
            NOW,
            NOW,
            NOW));
    Map<String, Object> data = service.adminGetKyc(adminPrincipal(), PHARMACY_ID);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> docs = (List<Map<String, Object>>) data.get("documents");
    assertThat(docs).hasSize(2);
    assertThat(docs.get(0).get("signed_url")).isNull();
    assertThat(docs.get(1).get("verified_by")).isEqualTo(ADMIN_ID.toString());
  }
}

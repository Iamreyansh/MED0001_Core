package com.nammamedmate.pharmacy.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyKycController;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyKycController.VerifyRequest;
import com.nammamedmate.pharmacy.adapter.in.web.PharmacyKycController;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcKycDocumentStore;
import com.nammamedmate.pharmacy.adapter.out.scan.GuardDutyDeferredVirusScanner;
import com.nammamedmate.pharmacy.adapter.out.scan.LoggingVirusScanner;
import com.nammamedmate.pharmacy.adapter.out.storage.LocalKycObjectStore;
import com.nammamedmate.pharmacy.adapter.out.storage.S3KycObjectStore;
import com.nammamedmate.pharmacy.application.AutoKycService;
import com.nammamedmate.pharmacy.application.PharmacyKycService;
import com.nammamedmate.pharmacy.application.port.out.KycDocumentStore.KycAccessAuditRecord;
import com.nammamedmate.pharmacy.application.port.out.KycDocumentStore.KycDocumentRecord;
import com.nammamedmate.pharmacy.application.port.out.KycDocumentStore.KycExpiryAlertRecord;
import com.nammamedmate.pharmacy.application.port.out.VirusScanner;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;

class PharmacyKycAdapterCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");
  private static final UUID PID = Ids.newId();
  private static final UUID DOC_ID = Ids.newId();
  private static final UUID ADMIN_ID = Ids.newId();

  // ─── Controller delegation ───────────────────────────────────────────────────

  @Test
  void pharmacyKycControllerDelegates() throws IOException {
    PharmacyKycService service = mock(PharmacyKycService.class);
    when(service.uploadDocument(any(), anyString(), any(), any(), any(), any()))
        .thenReturn(Map.of("document_id", DOC_ID.toString(), "status", "UPLOADED"));
    when(service.listDocuments(any())).thenReturn(Map.of("pharmacy_id", PID.toString()));
    when(service.deleteDocument(any(), any())).thenReturn(Map.of("deleted", true));
    when(service.submitKyc(any())).thenReturn(Map.of("status", "KYC_SUBMITTED"));

    PharmacyKycController controller = new PharmacyKycController(service);
    MedmatePrincipal principal =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, PID, TokenScope.FULL, "j");

    MockMultipartFile file =
        new MockMultipartFile("file", "test.pdf", "application/pdf", "PDF content".getBytes());

    assertThat(
            controller
                .uploadDocument(principal, "PAN_CARD", file, null)
                .getStatusCode()
                .is2xxSuccessful())
        .isTrue();
    assertThat(controller.listDocuments(principal).success()).isTrue();
    assertThat(controller.deleteDocument(principal, DOC_ID).success()).isTrue();
    assertThat(controller.submitKyc(principal, null).success()).isTrue();
    assertThat(controller.submitKyc(principal, new PharmacyKycController.EmptyBody()).success())
        .isTrue();
  }

  @Test
  void adminKycControllerDelegates() {
    PharmacyKycService service = mock(PharmacyKycService.class);
    AutoKycService autoKyc = mock(AutoKycService.class);
    when(service.adminGetKyc(any(), any())).thenReturn(Map.of("pharmacy_id", PID.toString()));
    when(service.adminVerifyDocument(any(), any(), any(), eq(true), any()))
        .thenReturn(Map.of("status", "VERIFIED"));
    when(service.adminVerifyDocument(any(), any(), any(), eq(false), any()))
        .thenReturn(Map.of("status", "REJECTED"));
    when(autoKyc.adminTriggerAutoVerify(any(), any(), any()))
        .thenReturn(Map.of("job_id", Ids.newId().toString()));
    when(autoKyc.adminGetAutoVerifyResult(any(), any(), any()))
        .thenReturn(Map.of("overall_status", "PASS"));

    AdminPharmacyKycController controller = new AdminPharmacyKycController(service, autoKyc);
    MedmatePrincipal adminPrincipal =
        new MedmatePrincipal(ADMIN_ID, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");

    ApiResponse<Map<String, Object>> get = controller.getKyc(adminPrincipal, PID);
    assertThat(get.success()).isTrue();

    ApiResponse<Map<String, Object>> verify =
        controller.verifyDocument(adminPrincipal, PID, DOC_ID, new VerifyRequest(true, null));
    assertThat(verify.success()).isTrue();

    ApiResponse<Map<String, Object>> reject =
        controller.verifyDocument(adminPrincipal, PID, DOC_ID, new VerifyRequest(false, "blurry"));
    assertThat(reject.success()).isTrue();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> controller.verifyDocument(adminPrincipal, PID, DOC_ID, null))
        .isInstanceOf(com.nammamedmate.kernel.error.AppException.class)
        .extracting(ex -> ((com.nammamedmate.kernel.error.AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                controller.verifyDocument(
                    adminPrincipal, PID, DOC_ID, new VerifyRequest(null, "x")))
        .isInstanceOf(com.nammamedmate.kernel.error.AppException.class)
        .extracting(ex -> ((com.nammamedmate.kernel.error.AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  // ─── LoggingVirusScanner ─────────────────────────────────────────────────────

  @Test
  void virusScannerPassesCleanFile() {
    LoggingVirusScanner scanner = new LoggingVirusScanner();
    scanner.scan("clean content".getBytes(), "clean.pdf");
  }

  @Test
  void virusScannerRejectsEicarInName() {
    LoggingVirusScanner scanner = new LoggingVirusScanner();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> scanner.scan("content".getBytes(), "eicar-test.pdf"))
        .isInstanceOf(VirusScanner.VirusScanException.class);
  }

  @Test
  void virusScannerRejectsEicarInContent() {
    LoggingVirusScanner scanner = new LoggingVirusScanner();
    byte[] content = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!".getBytes();
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> scanner.scan(content, "normal.pdf"))
        .isInstanceOf(VirusScanner.VirusScanException.class);
  }

  @Test
  void virusScannerHandlesNullContent() {
    new LoggingVirusScanner().scan(null, null);
  }

  @Test
  void guardDutyDeferredVirusScannerIsNoOp() {
    new GuardDutyDeferredVirusScanner().scan(new byte[] {1}, "a.pdf");
  }

  @Test
  void s3KycObjectStorePutsAndDeletesObject() {
    software.amazon.awssdk.services.s3.S3Client s3 =
        mock(software.amazon.awssdk.services.s3.S3Client.class);
    S3KycObjectStore store = new S3KycObjectStore(s3, "med0001-test-uploads");
    store.put("kyc/doc.pdf", "data".getBytes(), "application/pdf");
    org.mockito.Mockito.verify(s3)
        .putObject(
            any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),
            any(software.amazon.awssdk.core.sync.RequestBody.class));
    store.delete("kyc/doc.pdf");
    org.mockito.Mockito.verify(s3)
        .deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
  }

  @Test
  void localKycObjectStoreWritesAndDeletesFile() {
    LocalKycObjectStore store = new LocalKycObjectStore();
    String key = "kyc/" + PID + "/PAN_CARD/" + DOC_ID + ".pdf";
    store.put(key, "data".getBytes(), "application/pdf");
    store.delete(key);
  }

  // ─── JdbcKycDocumentStore ────────────────────────────────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  void jdbcKycDocumentStoreOperations() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcKycDocumentStore store = new JdbcKycDocumentStore(jdbc);

    // insert with non-null expiryDate and null verifiedAt
    KycDocumentRecord doc = sampleDoc(DOC_ID, PID, "UPLOADED");
    store.insert(doc);
    // insert with null expiryDate and non-null verifiedAt (covers both ternary branches)
    KycDocumentRecord docNullExpiry =
        new KycDocumentRecord(
            Ids.newId(),
            PID,
            "GSTIN_CERTIFICATE",
            "kyc/p/GSTIN/x.pdf",
            "g.pdf",
            512L,
            "application/pdf",
            "VERIFIED",
            null,
            null,
            ADMIN_ID,
            NOW,
            NOW,
            NOW);
    store.insert(docNullExpiry);
    // updateStatus with non-null verifiedAt
    store.updateStatus(DOC_ID, "VERIFIED", null, ADMIN_ID, NOW, NOW);
    // updateStatus with null verifiedAt (covers null branch)
    store.updateStatus(DOC_ID, "REJECTED", "blurry", null, null, NOW);
    store.softDelete(DOC_ID, NOW);
    store.setAllUploadedToUnderReview(PID, NOW);
    store.insertAccessAudit(new KycAccessAuditRecord(Ids.newId(), DOC_ID, PID, ADMIN_ID, NOW));
    store.insertExpiryAlert(
        new KycExpiryAlertRecord(
            Ids.newId(),
            DOC_ID,
            PID,
            NOW.plusSeconds(3600),
            "DRUG_LICENCE_EXPIRY_REMINDER_60",
            NOW));

    // findById — returns row
    when(jdbc.query(anyString(), any(RowMapper.class), eq(DOC_ID), eq(PID)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(mockDocRs(), 0)));
    assertThat(store.findById(DOC_ID, PID)).isPresent();

    // findByFileKey
    when(jdbc.query(anyString(), any(RowMapper.class), eq("kyc/p/doc.pdf")))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(mockDocRs(), 0)));
    assertThat(store.findByFileKey("kyc/p/doc.pdf")).isPresent();

    // findById — returns empty
    when(jdbc.query(anyString(), any(RowMapper.class), eq(Ids.newId()), eq(PID)))
        .thenReturn(List.of());

    // findActiveByPharmacy
    when(jdbc.query(anyString(), any(RowMapper.class), eq(PID)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(mockDocRs(), 0)));
    assertThat(store.findActiveByPharmacy(PID)).hasSize(1);

    // countByPharmacyAndStatuses
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(2);
    assertThat(store.countByPharmacyAndStatuses(PID, List.of("UPLOADED", "VERIFIED"))).isEqualTo(2);

    // count returns null
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(null);
    assertThat(store.countByPharmacyAndStatuses(PID, List.of("UPLOADED"))).isEqualTo(0);

    // empty statuses list
    assertThat(store.countByPharmacyAndStatuses(PID, List.of())).isEqualTo(0);

    // existsExpiryAlert
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(DOC_ID), anyString()))
        .thenReturn(1);
    assertThat(store.existsExpiryAlert(DOC_ID, "DRUG_LICENCE_EXPIRY_REMINDER_60")).isTrue();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(DOC_ID), anyString()))
        .thenReturn(0);
    assertThat(store.existsExpiryAlert(DOC_ID, "DRUG_LICENCE_EXPIRY_REMINDER_30")).isFalse();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(DOC_ID), anyString()))
        .thenReturn(null);
    assertThat(store.existsExpiryAlert(DOC_ID, "FSSAI_EXPIRY_REMINDER_60")).isFalse();

    // mapRow with null expiry and null verifiedAt
    KycDocumentRecord mapped = store.findById(DOC_ID, PID).orElseThrow();
    assertThat(mapped.documentType()).isEqualTo("DRUG_LICENCE");
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcKycDocRowMapperWithNonNullVerifiedAt() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcKycDocumentStore store = new JdbcKycDocumentStore(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(DOC_ID), eq(PID)))
        .thenAnswer(
            inv ->
                List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(mockDocRsWithVerifiedAt(), 0)));
    KycDocumentRecord doc = store.findById(DOC_ID, PID).orElseThrow();
    assertThat(doc.verifiedAt()).isNotNull();
  }

  @Test
  void localKycObjectStoreThrowsOnIOFailure(@TempDir Path tempDir) throws Exception {
    // Create a FILE at "block" so that "block/sub" cannot be created as a directory
    Path blockingFile = tempDir.resolve("block");
    Files.writeString(blockingFile, "occupied");
    LocalKycObjectStore store = new LocalKycObjectStore(blockingFile.resolve("sub")) {};
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> store.put("key", "data".getBytes(), "application/pdf"))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void localKycObjectStoreDeleteThrowsWhenTargetIsNonEmptyDirectory(@TempDir Path tempDir)
      throws Exception {
    Path dir = tempDir.resolve("kyc-base");
    Files.createDirectories(dir);
    LocalKycObjectStore real = new LocalKycObjectStore(dir) {};
    Path nested = dir.resolve("nested-as-dir");
    Files.createDirectories(nested);
    Files.writeString(nested.resolve("child"), "x");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> real.delete("nested/as/dir"))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcKycDocRowMapperWithNullOptionalFields() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcKycDocumentStore store = new JdbcKycDocumentStore(jdbc);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(DOC_ID), eq(PID)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mockDocRsWithNulls();
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    KycDocumentRecord doc = store.findById(DOC_ID, PID).orElseThrow();
    assertThat(doc.expiryDate()).isNull();
    assertThat(doc.verifiedAt()).isNull();
  }

  private static KycDocumentRecord sampleDoc(UUID id, UUID pharmacyId, String status) {
    return new KycDocumentRecord(
        id,
        pharmacyId,
        "DRUG_LICENCE",
        "kyc/" + pharmacyId + "/DRUG_LICENCE/" + id + ".pdf",
        "dl.pdf",
        1024L,
        "application/pdf",
        status,
        null,
        LocalDate.of(2027, 6, 30),
        null,
        null,
        NOW,
        NOW);
  }

  private static ResultSet mockDocRsWithVerifiedAt() throws Exception {
    ResultSet rs = mockDocRs();
    when(rs.getTimestamp("verified_at")).thenReturn(Timestamp.from(NOW));
    return rs;
  }

  private static ResultSet mockDocRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(DOC_ID);
    when(rs.getObject("pharmacy_id")).thenReturn(PID);
    when(rs.getString("document_type")).thenReturn("DRUG_LICENCE");
    when(rs.getString("file_key")).thenReturn("kyc/p/DL/x.pdf");
    when(rs.getString("file_name")).thenReturn("dl.pdf");
    when(rs.getLong("file_size_bytes")).thenReturn(1024L);
    when(rs.getString("file_mime_type")).thenReturn("application/pdf");
    when(rs.getString("status")).thenReturn("UPLOADED");
    when(rs.getString("rejection_reason")).thenReturn(null);
    when(rs.getDate("expiry_date")).thenReturn(Date.valueOf("2027-06-30"));
    when(rs.getObject("verified_by")).thenReturn(null);
    when(rs.getTimestamp("verified_at")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
    return rs;
  }

  private static ResultSet mockDocRsWithNulls() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(DOC_ID);
    when(rs.getObject("pharmacy_id")).thenReturn(PID);
    when(rs.getString("document_type")).thenReturn("PAN_CARD");
    when(rs.getString("file_key")).thenReturn("kyc/p/PAN/x.pdf");
    when(rs.getString("file_name")).thenReturn("pan.pdf");
    when(rs.getLong("file_size_bytes")).thenReturn(512L);
    when(rs.getString("file_mime_type")).thenReturn("image/jpeg");
    when(rs.getString("status")).thenReturn("UPLOADED");
    when(rs.getString("rejection_reason")).thenReturn(null);
    when(rs.getDate("expiry_date")).thenReturn(null);
    when(rs.getObject("verified_by")).thenReturn(null);
    when(rs.getTimestamp("verified_at")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
    return rs;
  }

  // ─── Auto-KYC adapters (STORY-003) ───────────────────────────────────────────

  @Test
  void autoKycOutboxConsumerRoutesEvents() {
    AutoKycService autoKyc = mock(AutoKycService.class);
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    com.nammamedmate.pharmacy.adapter.in.messaging.AutoKycOutboxConsumer consumer =
        new com.nammamedmate.pharmacy.adapter.in.messaging.AutoKycOutboxConsumer(autoKyc, mapper);

    UUID pharmacyId = Ids.newId();
    UUID verificationId = Ids.newId();
    com.nammamedmate.messaging.InMemoryOutboxStore outboxStore =
        new com.nammamedmate.messaging.InMemoryOutboxStore();
    com.nammamedmate.messaging.OutboxPublisher publisher =
        new com.nammamedmate.messaging.OutboxPublisher(outboxStore, mapper);
    publisher.publish(
        com.nammamedmate.messaging.DomainEvent.of(
            "pharmacy.kyc.auto_verify_requested",
            "pharmacy",
            pharmacyId,
            Map.of("pharmacy_id", pharmacyId.toString())));
    consumer.accept(outboxStore.all().getFirst());
    org.mockito.Mockito.verify(autoKyc).handleAutoVerifyRequested(pharmacyId);

    publisher.publish(
        com.nammamedmate.messaging.DomainEvent.of(
            "pharmacy.kyc.async_check_requested",
            "pharmacy",
            pharmacyId,
            Map.of("verification_id", verificationId.toString())));
    consumer.accept(outboxStore.all().get(1));
    org.mockito.Mockito.verify(autoKyc).processAsyncCheck(verificationId);

    consumer.accept(null);
    consumer.accept(
        new com.nammamedmate.messaging.OutboxMessage(Ids.newId(), null, "{}", NOW, false));
    consumer.accept(com.nammamedmate.messaging.OutboxMessage.pending("other.event", "{}"));
    consumer.accept(
        com.nammamedmate.messaging.OutboxMessage.pending(
            "pharmacy.kyc.async_check_requested", "not-json"));
    publisher.publish(
        com.nammamedmate.messaging.DomainEvent.of(
            "pharmacy.kyc.async_check_requested",
            "pharmacy",
            pharmacyId,
            Map.of("pharmacy_id", pharmacyId.toString())));
    consumer.accept(outboxStore.all().get(2));
    consumer.accept(
        com.nammamedmate.messaging.OutboxMessage.pending(
            "pharmacy.kyc.auto_verify_requested", "bad-json"));
  }

  @Test
  void internalKycWebhookControllerDelegates() {
    AutoKycService autoKyc = mock(AutoKycService.class);
    when(autoKyc.handleWebhookCallback(any(), any()))
        .thenReturn(Map.of("acknowledged", true, "verification_id", DOC_ID.toString()));
    com.nammamedmate.pharmacy.adapter.in.web.InternalKycWebhookController controller =
        new com.nammamedmate.pharmacy.adapter.in.web.InternalKycWebhookController(autoKyc);
    org.springframework.mock.web.MockHttpServletRequest request =
        new org.springframework.mock.web.MockHttpServletRequest();
    request.setAttribute(
        com.nammamedmate.kernel.webhook.WebhookRawBodyFilter.CACHED_BODY_ATTR,
        "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(controller.webhookCallback("sig", request).success()).isTrue();
  }

  @Test
  void adminAutoVerifyEndpointsDelegate() {
    PharmacyKycService service = mock(PharmacyKycService.class);
    AutoKycService autoKyc = mock(AutoKycService.class);
    when(autoKyc.adminTriggerAutoVerify(any(), any(), any()))
        .thenReturn(Map.of("job_id", DOC_ID.toString()));
    when(autoKyc.adminGetAutoVerifyResult(any(), any(), any()))
        .thenReturn(Map.of("overall_status", "PASS"));
    AdminPharmacyKycController controller = new AdminPharmacyKycController(service, autoKyc);
    MedmatePrincipal admin =
        new MedmatePrincipal(ADMIN_ID, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    assertThat(
            controller
                .triggerAutoVerify(
                    admin, PID, new AdminPharmacyKycController.AutoVerifyRequest(null))
                .success())
        .isTrue();
    assertThat(
            controller
                .triggerAutoVerify(
                    admin, PID, new AdminPharmacyKycController.AutoVerifyRequest(List.of("GSTIN")))
                .success())
        .isTrue();
    assertThat(controller.getAutoVerifyResult(admin, PID, DOC_ID).success()).isTrue();
    assertThat(controller.triggerAutoVerify(admin, PID, null).success()).isTrue();
  }

  @Test
  void stubKycClientsCoverEdgeCases() {
    var gstin = new com.nammamedmate.pharmacy.adapter.out.kyc.StubGstinVerificationClient();
    assertThat(gstin.verifyGstin(null, null).status()).isEqualTo("PASS");
    assertThat(gstin.verifyGstin("X", null).status()).isEqualTo("PASS");
    assertThat(gstin.verifyGstin("27invalidGST", "Biz").status()).isEqualTo("FAIL");
    assertThat(gstin.verifyGstin("27errorGST", "Biz").status()).isEqualTo("ERROR");
    assertThat(gstin.verifyGstin("27timeoutGST", "Biz").status()).isEqualTo("ERROR");
    assertThat(gstin.verifyGstin("27TEST", null).details())
        .containsEntry("business_name_registered", "REGISTERED NAME");

    var drug = new com.nammamedmate.pharmacy.adapter.out.kyc.StubDrugLicenceVerificationClient();
    assertThat(drug.verifyDrugLicence(null, null).status()).isEqualTo("FAIL");
    assertThat(drug.verifyDrugLicence("DL-XX-1", "XX").status()).isEqualTo("FAIL");
    assertThat(drug.verifyDrugLicence("DL-fail-1", "MH").status()).isEqualTo("FAIL");
    assertThat(drug.verifyDrugLicence("DL-error-1", "MH").status()).isEqualTo("ERROR");
    assertThat(drug.verifyDrugLicence("DL-expiring-1", "MH").status()).isEqualTo("PASS");

    var fssai = new com.nammamedmate.pharmacy.adapter.out.kyc.StubFssaiVerificationClient();
    assertThat(fssai.verifyFssai(null).status()).isEqualTo("PASS");
    assertThat(fssai.verifyFssai("error-fssai").status()).isEqualTo("ERROR");
    assertThat(fssai.verifyFssai("fail-fssai").status()).isEqualTo("FAIL");
  }

  @Test
  void kycDomainHelpers() {
    assertThat(com.nammamedmate.pharmacy.domain.KycRequestSanitizer.sanitise(null)).isEmpty();
    assertThat(com.nammamedmate.pharmacy.domain.KycRequestSanitizer.sanitise(Map.of())).isEmpty();
    assertThat(
            com.nammamedmate.pharmacy.domain.KycRequestSanitizer.sanitise(
                Map.of("api_key", "secret", "nested", Map.of("token", "x"))))
        .containsEntry("api_key", "[REDACTED]");
    assertThat(com.nammamedmate.pharmacy.domain.BusinessNameMatcher.tokenDistance("", "")).isZero();
    assertThat(
            com.nammamedmate.pharmacy.domain.BusinessNameMatcher.isSignificantMismatch(
                "Alpha Beta Gamma Delta Epsilon Zeta", "One Two Three Four Five Six Seven"))
        .isTrue();
  }

  @Test
  void stubPortsInstantiate() {
    assertThat(new com.nammamedmate.pharmacy.adapter.out.kyc.StubGstinVerificationClient())
        .isNotNull();
    assertThat(new com.nammamedmate.pharmacy.adapter.out.kyc.StubDrugLicenceVerificationClient())
        .isNotNull();
    assertThat(new com.nammamedmate.pharmacy.adapter.out.kyc.StubFssaiVerificationClient())
        .isNotNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcAutoKycStoresOperations() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    com.nammamedmate.pharmacy.adapter.out.persistence.JdbcAutoKycJobStore jobStore =
        new com.nammamedmate.pharmacy.adapter.out.persistence.JdbcAutoKycJobStore(jdbc);
    com.nammamedmate.pharmacy.adapter.out.persistence.JdbcKycVerificationStore verificationStore =
        new com.nammamedmate.pharmacy.adapter.out.persistence.JdbcKycVerificationStore(
            jdbc, mapper);
    com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPincodeZoneStore pincodeZoneStore =
        new com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPincodeZoneStore(jdbc);

    UUID jobId = Ids.newId();
    jobStore.insert(
        new com.nammamedmate.pharmacy.application.port.out.AutoKycJobStore.AutoKycJobRecord(
            jobId, PID, ADMIN_ID, "ADMIN", "PENDING", false, NOW, NOW));
    jobStore.insert(
        new com.nammamedmate.pharmacy.application.port.out.AutoKycJobStore.AutoKycJobRecord(
            Ids.newId(), PID, ADMIN_ID, "ADMIN", "PENDING", false, NOW, null));
    jobStore.updateOverallStatus(jobId, "PARTIAL", NOW);
    jobStore.updateOverallStatus(Ids.newId(), "PENDING", null);
    jobStore.markAutoActivated(jobId, NOW);

    verificationStore.insert(
        new com.nammamedmate.pharmacy.application.port.out.KycVerificationStore
            .KycVerificationRecord(
            Ids.newId(),
            PID,
            jobId,
            "GSTIN",
            "GSTN_SANDBOX_API",
            Map.of("gstin", "27TEST"),
            Map.of("ok", true),
            "PENDING",
            Map.of("gstin", "27TEST"),
            List.of(Map.of("flag", "X")),
            0,
            NOW,
            NOW,
            NOW));
    verificationStore.insert(
        new com.nammamedmate.pharmacy.application.port.out.KycVerificationStore
            .KycVerificationRecord(
            Ids.newId(),
            PID,
            jobId,
            "FSSAI",
            "FSSAI_PORTAL_API",
            Map.of("licence_number", "11223344556677"),
            null,
            "PENDING",
            null,
            null,
            0,
            null,
            null,
            NOW));
    verificationStore.updateResult(
        Ids.newId(), "PASS", Map.of("ok", true), Map.of("gstin", "27TEST"), List.of(), 1, NOW, NOW);
    verificationStore.updateResult(Ids.newId(), "ERROR", null, null, null, 0, null, null);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(jobId)))
        .thenAnswer(
            inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(mockAutoKycJobRs(), 0)));
    assertThat(jobStore.findById(jobId)).isPresent();
    when(jdbc.query(contains("ORDER BY triggered_at DESC"), any(RowMapper.class), eq(PID)))
        .thenAnswer(
            inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(mockAutoKycJobRs(), 0)));
    assertThat(jobStore.findLatestByPharmacy(PID)).isPresent();
    when(jdbc.query(contains("overall_status IN"), any(RowMapper.class), eq(PID)))
        .thenAnswer(
            inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(mockAutoKycJobRs(), 0)));
    assertThat(jobStore.findInProgressByPharmacy(PID)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(PID))).thenReturn(List.of());
    assertThat(pincodeZoneStore.findZoneIdByPincode("560001")).isEmpty();
    assertThat(pincodeZoneStore.findZoneIdByPincode(null)).isEmpty();
    assertThat(pincodeZoneStore.findZoneIdByPincode("  ")).isEmpty();
    when(jdbc.query(contains("pincode_zone_mapping"), any(RowMapper.class), eq("560001")))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("zone_id")).thenReturn(ZONE_ID);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(pincodeZoneStore.findZoneIdByPincode("560001")).contains(ZONE_ID);

    ResultSet verificationRs = mockVerificationRs();
    when(jdbc.query(contains("kyc_verifications WHERE id"), any(RowMapper.class), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(verificationRs, 0)));
    UUID verificationId = (UUID) verificationRs.getObject("id");
    assertThat(verificationStore.findById(verificationId)).isPresent();
    when(jdbc.query(contains("WHERE job_id = ?"), any(RowMapper.class), eq(jobId)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(verificationRs, 0)));
    assertThat(verificationStore.findByJobId(jobId)).hasSize(1);
    when(jdbc.query(
            contains("verification_type = ?"), any(RowMapper.class), eq(jobId), eq("GSTIN")))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(verificationRs, 0)));
    assertThat(verificationStore.findByJobAndType(jobId, "GSTIN")).isPresent();
    when(jdbc.query(contains("next_retry_at"), any(RowMapper.class), any(), anyInt()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(verificationRs, 0)));
    assertThat(verificationStore.findDueRetries(NOW, 5)).hasSize(1);
    when(jdbc.query(
            contains("status = 'PENDING' AND created_at"), any(RowMapper.class), any(), anyInt()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(verificationRs, 0)));
    assertThat(verificationStore.findStalePending(NOW, 5)).hasSize(1);

    ResultSet blankJsonRs = mockVerificationRs();
    when(blankJsonRs.getString("request_payload")).thenReturn(null);
    when(blankJsonRs.getString("response_payload")).thenReturn(null);
    when(blankJsonRs.getString("details")).thenReturn(null);
    when(blankJsonRs.getString("admin_flags")).thenReturn(null);
    when(jdbc.query(contains("kyc_verifications WHERE id"), any(RowMapper.class), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(blankJsonRs, 0)));
    assertThat(verificationStore.findById((UUID) blankJsonRs.getObject("id"))).isPresent();

    ResultSet blankStringJsonRs = mockVerificationRs();
    when(blankStringJsonRs.getString("request_payload")).thenReturn("   ");
    when(blankStringJsonRs.getString("response_payload")).thenReturn("   ");
    when(blankStringJsonRs.getString("details")).thenReturn("   ");
    when(blankStringJsonRs.getString("admin_flags")).thenReturn("   ");
    when(jdbc.query(contains("kyc_verifications WHERE id"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(blankStringJsonRs, 0)));
    assertThat(verificationStore.findById((UUID) blankStringJsonRs.getObject("id"))).isPresent();

    ResultSet fullJsonRs = mockVerificationRs();
    when(fullJsonRs.getString("response_payload")).thenReturn("{\"ok\":true}");
    when(fullJsonRs.getString("details")).thenReturn("{\"gstin\":\"27TEST\"}");
    when(fullJsonRs.getString("admin_flags")).thenReturn("[{\"flag\":\"X\"}]");
    when(jdbc.query(contains("kyc_verifications WHERE id"), any(RowMapper.class), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(fullJsonRs, 0)));
    assertThat(verificationStore.findById((UUID) fullJsonRs.getObject("id"))).isPresent();

    com.fasterxml.jackson.databind.ObjectMapper nullFlagsMapper =
        mock(com.fasterxml.jackson.databind.ObjectMapper.class);
    when(nullFlagsMapper.writeValueAsString(any()))
        .thenAnswer(
            inv ->
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(inv.getArgument(0)));
    when(nullFlagsMapper.readValue(
            eq("[]"), any(com.fasterxml.jackson.core.type.TypeReference.class)))
        .thenReturn(null);
    com.nammamedmate.pharmacy.adapter.out.persistence.JdbcKycVerificationStore nullFlagsStore =
        new com.nammamedmate.pharmacy.adapter.out.persistence.JdbcKycVerificationStore(
            jdbc, nullFlagsMapper);
    ResultSet flagsRs = mockVerificationRs();
    when(flagsRs.getString("admin_flags")).thenReturn("[]");
    when(jdbc.query(contains("kyc_verifications WHERE id"), any(RowMapper.class), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(flagsRs, 0)));
    assertThat(nullFlagsStore.findById((UUID) flagsRs.getObject("id"))).isPresent();

    ResultSet completedJobRs = mockAutoKycJobRs();
    when(completedJobRs.getTimestamp("completed_at")).thenReturn(Timestamp.from(NOW));
    when(jdbc.query(anyString(), any(RowMapper.class), eq(jobId)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(completedJobRs, 0)));
    assertThat(jobStore.findById(jobId).orElseThrow().completedAt()).isEqualTo(NOW);
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcKycVerificationStoreThrowsOnInvalidJson() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    com.nammamedmate.pharmacy.adapter.out.persistence.JdbcKycVerificationStore store =
        new com.nammamedmate.pharmacy.adapter.out.persistence.JdbcKycVerificationStore(
            jdbc, mapper);
    ResultSet badRs = mock(ResultSet.class);
    UUID id = Ids.newId();
    when(badRs.getObject("id")).thenReturn(id);
    when(badRs.getObject("pharmacy_id")).thenReturn(PID);
    when(badRs.getObject("job_id")).thenReturn(Ids.newId());
    when(badRs.getString("verification_type")).thenReturn("GSTIN");
    when(badRs.getString("api_provider")).thenReturn("GSTN_SANDBOX_API");
    when(badRs.getString("request_payload")).thenReturn("{bad-json");
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(badRs, 0)));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.findById(id))
        .isInstanceOf(IllegalStateException.class);

    ResultSet flagsRs = mockVerificationRs();
    when(flagsRs.getString("admin_flags")).thenReturn("{invalid");
    when(jdbc.query(contains("kyc_verifications WHERE id"), any(RowMapper.class), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(flagsRs, 0)));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.findById(id))
        .isInstanceOf(IllegalStateException.class);

    com.fasterxml.jackson.databind.ObjectMapper broken =
        mock(com.fasterxml.jackson.databind.ObjectMapper.class);
    when(broken.writeValueAsString(any()))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") {});
    com.nammamedmate.pharmacy.adapter.out.persistence.JdbcKycVerificationStore brokenStore =
        new com.nammamedmate.pharmacy.adapter.out.persistence.JdbcKycVerificationStore(
            jdbc, broken);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                brokenStore.insert(
                    new com.nammamedmate.pharmacy.application.port.out.KycVerificationStore
                        .KycVerificationRecord(
                        Ids.newId(),
                        PID,
                        Ids.newId(),
                        "GSTIN",
                        "GSTN_SANDBOX_API",
                        Map.of("gstin", "27TEST"),
                        null,
                        "PENDING",
                        null,
                        List.of(),
                        0,
                        null,
                        null,
                        NOW)))
        .isInstanceOf(IllegalStateException.class);

    com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacyRegistrationStore pharmacyStore =
        new com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacyRegistrationStore(
            jdbc, mapper);
    pharmacyStore.activateAfterAutoKyc(PID, ZONE_ID, NOW);
    verify(jdbc).update(contains("is_online = TRUE"), eq(ZONE_ID), any(), any(), eq(PID));
  }

  private static ResultSet mockAutoKycJobRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    UUID jobId = Ids.newId();
    when(rs.getObject("id")).thenReturn(jobId);
    when(rs.getObject("pharmacy_id")).thenReturn(PID);
    when(rs.getObject("triggered_by")).thenReturn(ADMIN_ID);
    when(rs.getString("trigger_source")).thenReturn("ADMIN");
    when(rs.getString("overall_status")).thenReturn("PENDING");
    when(rs.getBoolean("auto_activated")).thenReturn(false);
    when(rs.getTimestamp("triggered_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("completed_at")).thenReturn(null);
    return rs;
  }

  private static ResultSet mockVerificationRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    UUID id = Ids.newId();
    UUID jobId = Ids.newId();
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("pharmacy_id")).thenReturn(PID);
    when(rs.getObject("job_id")).thenReturn(jobId);
    when(rs.getString("verification_type")).thenReturn("GSTIN");
    when(rs.getString("api_provider")).thenReturn("GSTN_SANDBOX_API");
    when(rs.getString("request_payload")).thenReturn("{\"gstin\":\"27TEST\"}");
    when(rs.getString("response_payload")).thenReturn("{\"ok\":true}");
    when(rs.getString("status")).thenReturn("PASS");
    when(rs.getString("details")).thenReturn("{\"gstin\":\"27TEST\"}");
    when(rs.getString("admin_flags")).thenReturn("[]");
    when(rs.getInt("retry_count")).thenReturn(0);
    when(rs.getTimestamp("next_retry_at")).thenReturn(null);
    when(rs.getTimestamp("verified_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    return rs;
  }

  private static final UUID ZONE_ID = Ids.newId();
}

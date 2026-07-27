package com.nammamedmate.pharmacy.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    when(service.adminGetKyc(any(), any())).thenReturn(Map.of("pharmacy_id", PID.toString()));
    when(service.adminVerifyDocument(any(), any(), any(), eq(true), any()))
        .thenReturn(Map.of("status", "VERIFIED"));
    when(service.adminVerifyDocument(any(), any(), any(), eq(false), any()))
        .thenReturn(Map.of("status", "REJECTED"));

    AdminPharmacyKycController controller = new AdminPharmacyKycController(service);
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
}

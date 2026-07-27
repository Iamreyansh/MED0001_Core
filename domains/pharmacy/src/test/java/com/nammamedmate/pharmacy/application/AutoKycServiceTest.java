package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.adapter.out.kyc.StubDrugLicenceVerificationClient;
import com.nammamedmate.pharmacy.adapter.out.kyc.StubFssaiVerificationClient;
import com.nammamedmate.pharmacy.adapter.out.kyc.StubGstinVerificationClient;
import com.nammamedmate.pharmacy.application.port.out.AutoKycJobStore;
import com.nammamedmate.pharmacy.application.port.out.AutoKycJobStore.AutoKycJobRecord;
import com.nammamedmate.pharmacy.application.port.out.KycVerificationStore;
import com.nammamedmate.pharmacy.application.port.out.KycVerificationStore.KycVerificationRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore.PharmacyRecord;
import com.nammamedmate.pharmacy.application.port.out.PincodeZoneStore;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutoKycServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");
  private static final String WEBHOOK_SECRET = "test-webhook-secret";
  private static final UUID PHARMACY_ID = Ids.newId();
  private static final UUID ADMIN_ID = Ids.newId();
  private static final UUID ZONE_ID = UUID.fromString("b0000001-0000-4000-8000-000000000001");

  private FakePharmacyStore pharmacyStore;
  private FakeAutoKycJobStore jobStore;
  private FakeKycVerificationStore verificationStore;
  private FakePincodeZoneStore pincodeZoneStore;
  private InMemoryOutboxStore outboxStore;
  private OutboxPublisher outbox;
  private MutableClock clock;
  private AutoKycService service;
  private AutoKycRetryWorker retryWorker;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(NOW);
    pharmacyStore = new FakePharmacyStore();
    jobStore = new FakeAutoKycJobStore();
    verificationStore = new FakeKycVerificationStore();
    pincodeZoneStore = new FakePincodeZoneStore();
    outboxStore = new InMemoryOutboxStore();
    outbox = new OutboxPublisher(outboxStore, new ObjectMapper());
    pharmacyStore.save(kycSubmittedPharmacy(PHARMACY_ID));
    pincodeZoneStore.zoneByPincode.put("560001", ZONE_ID);
    service = newService(true);
    retryWorker = new AutoKycRetryWorker(service, verificationStore, clock);
  }

  // ─── 1. Admin trigger accepted shape ─────────────────────────────────────────

  @Test
  void adminTriggerRunsAllChecksAndReturnsAcceptedShape() {
    Map<String, Object> data = service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, null);

    assertThat(data.get("pharmacy_id")).isEqualTo(PHARMACY_ID.toString());
    assertThat(data.get("job_id")).isNotNull();
    assertThat(data.get("checks_triggered"))
        .asList()
        .containsExactlyInAnyOrder("GSTIN", "DRUG_LICENCE", "FSSAI");
    assertThat(data.get("estimated_completion_minutes"))
        .isEqualTo(AutoKycService.ESTIMATED_COMPLETION_MINUTES);

    @SuppressWarnings("unchecked")
    Map<String, Object> gstinResult = (Map<String, Object>) data.get("gstin_result");
    assertThat(gstinResult.get("status")).isEqualTo("PASS");

    @SuppressWarnings("unchecked")
    Map<String, Object> drugResult = (Map<String, Object>) data.get("drug_licence_result");
    assertThat(drugResult.get("status")).isEqualTo("PENDING");

    @SuppressWarnings("unchecked")
    Map<String, Object> fssaiResult = (Map<String, Object>) data.get("fssai_result");
    assertThat(fssaiResult.get("status")).isEqualTo("PENDING");

    assertThat(jobStore.jobs).hasSize(1);
    assertThat(verificationStore.records).hasSize(3);
    assertThat(outboxStore.all()).isNotEmpty();
  }

  // ─── 2. All checks pass → auto-activate ──────────────────────────────────────

  @Test
  void allChecksPassAutoActivatesPharmacy() {
    service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, null);

    PharmacyRecord updated = pharmacyStore.findById(PHARMACY_ID).orElseThrow();
    assertThat(updated.status()).isEqualTo("ACTIVE");
    assertThat(updated.zoneId()).isEqualTo(ZONE_ID);
    assertThat(updated.online()).isTrue();

    AutoKycJobRecord job = jobStore.findLatestByPharmacy(PHARMACY_ID).orElseThrow();
    assertThat(job.overallStatus()).isEqualTo("PASS");
    assertThat(job.autoActivated()).isTrue();
    assertThat(job.completedAt()).isEqualTo(NOW);

    assertThat(outboxStore.all().stream().map(m -> m.type()))
        .contains("pharmacy.kyc.auto_activated", "pharmacy.notification.welcome");
  }

  // ─── 3. FSSAI fail → manual queue ────────────────────────────────────────────

  @Test
  void fssaiFailRoutesToManualQueue() {
    pharmacyStore.save(withFssai(kycSubmittedPharmacy(PHARMACY_ID), "fail-fssai-licence"));

    service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, List.of("FSSAI"));

    PharmacyRecord updated = pharmacyStore.findById(PHARMACY_ID).orElseThrow();
    assertThat(updated.status()).isEqualTo("KYC_SUBMITTED");

    AutoKycJobRecord job = jobStore.findLatestByPharmacy(PHARMACY_ID).orElseThrow();
    assertThat(job.overallStatus()).isEqualTo("FAIL");

    KycVerificationRecord fssai =
        verificationStore.findByJobAndType(job.id(), "FSSAI").orElseThrow();
    assertThat(fssai.status()).isEqualTo("FAIL");

    assertThat(outboxStore.all().stream().map(m -> m.type()))
        .contains("pharmacy.kyc.manual_review_required");
    assertThat(outboxStore.all().stream().map(m -> m.type()))
        .doesNotContain("pharmacy.kyc.auto_activated");
  }

  // ─── 4. GSTIN business name mismatch → WARN flag ─────────────────────────────

  @Test
  void gstinBusinessNameMismatchAddsWarnFlag() {
    pharmacyStore.save(withGstin(kycSubmittedPharmacy(PHARMACY_ID), "27mismatchAABCS1429B1ZB"));

    Map<String, Object> trigger =
        service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, List.of("GSTIN"));
    UUID jobId = UUID.fromString((String) trigger.get("job_id"));

    Map<String, Object> result =
        service.adminGetAutoVerifyResult(adminPrincipal(), PHARMACY_ID, jobId);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> flags = (List<Map<String, Object>>) result.get("admin_flags");
    assertThat(flags)
        .anySatisfy(flag -> assertThat(flag.get("flag")).isEqualTo("BUSINESS_NAME_MISMATCH"));

    KycVerificationRecord gstin = verificationStore.findByJobAndType(jobId, "GSTIN").orElseThrow();
    assertThat(gstin.adminFlags()).isNotEmpty();
  }

  // ─── 5. Drug licence expiring soon → FAIL ────────────────────────────────────

  @Test
  void drugLicenceExpiringSoonFailsCheck() {
    pharmacyStore.save(
        withDrugLicence(kycSubmittedPharmacy(PHARMACY_ID), "DL-MH-expiring-12345", "MH"));

    Map<String, Object> trigger =
        service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, List.of("DRUG_LICENCE"));
    UUID jobId = UUID.fromString((String) trigger.get("job_id"));

    KycVerificationRecord drug =
        verificationStore.findByJobAndType(jobId, "DRUG_LICENCE").orElseThrow();
    assertThat(drug.status()).isEqualTo("FAIL");
    assertThat(drug.details()).containsEntry("reason", "LICENCE_EXPIRING_SOON");

    AutoKycJobRecord job = jobStore.findById(jobId).orElseThrow();
    assertThat(job.overallStatus()).isEqualTo("FAIL");
  }

  // ─── 6. Drug licence transient error → retries → manual queue ─────────────────

  @Test
  void drugLicenceTransientErrorRetriesThenManualQueue() {
    pharmacyStore.save(
        withDrugLicence(kycSubmittedPharmacy(PHARMACY_ID), "DL-MH-error-12345", "MH"));

    service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, List.of("DRUG_LICENCE"));

    KycVerificationRecord afterFirst =
        verificationStore
            .findByJobAndType(
                jobStore.findLatestByPharmacy(PHARMACY_ID).orElseThrow().id(), "DRUG_LICENCE")
            .orElseThrow();
    assertThat(afterFirst.status()).isEqualTo("ERROR");
    assertThat(afterFirst.retryCount()).isEqualTo(1);
    assertThat(afterFirst.nextRetryAt()).isEqualTo(NOW.plusSeconds(10));

    clock.advance(Duration.ofSeconds(10));
    assertThat(retryWorker.processDueRetries()).isEqualTo(1);

    clock.advance(Duration.ofSeconds(30));
    assertThat(retryWorker.processDueRetries()).isEqualTo(1);

    clock.advance(Duration.ofSeconds(90));
    assertThat(retryWorker.processDueRetries()).isEqualTo(1);

    KycVerificationRecord exhausted =
        verificationStore
            .findByJobAndType(
                jobStore.findLatestByPharmacy(PHARMACY_ID).orElseThrow().id(), "DRUG_LICENCE")
            .orElseThrow();
    assertThat(exhausted.retryCount()).isEqualTo(3);
    assertThat(exhausted.verifiedAt()).isNotNull();

    assertThat(outboxStore.all().stream().map(m -> m.type()))
        .contains("pharmacy.kyc.manual_review_required");
  }

  // ─── 7. Feature flag disabled ────────────────────────────────────────────────

  @Test
  void featureFlagDisabledReturns503() {
    AutoKycService disabled = newService(false);

    assertThatThrownBy(() -> disabled.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, null))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("FEATURE_FLAG_DISABLED");
              assertThat(app.httpStatus()).isEqualTo(503);
            });
  }

  // ─── 8. Invalid check type ───────────────────────────────────────────────────

  @Test
  void invalidCheckTypeReturns400() {
    assertThatThrownBy(
            () ->
                service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, List.of("PAN_CARD")))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("INVALID_CHECK_TYPE");
              assertThat(app.httpStatus()).isEqualTo(400);
            });
  }

  // ─── 9. KYC not submitted ────────────────────────────────────────────────────

  @Test
  void kycNotSubmittedReturns409() {
    pharmacyStore.save(pharmacyWithStatus(PHARMACY_ID, "PENDING_KYC"));

    assertThatThrownBy(() -> service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, null))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("KYC_NOT_SUBMITTED");
              assertThat(app.httpStatus()).isEqualTo(409);
            });
  }

  // ─── 10. Auto-KYC already in progress ────────────────────────────────────────

  @Test
  void autoKycInProgressReturns409() {
    UUID jobId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PENDING", false, NOW, null));

    assertThatThrownBy(() -> service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, null))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("AUTO_KYC_IN_PROGRESS");
              assertThat(app.httpStatus()).isEqualTo(409);
            });
  }

  // ─── 11. Admin get result — no secrets in stored payloads ────────────────────

  @Test
  void adminGetResultReturnsChecksWithoutSecrets() {
    Map<String, Object> trigger =
        service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, null);
    UUID jobId = UUID.fromString((String) trigger.get("job_id"));

    Map<String, Object> result =
        service.adminGetAutoVerifyResult(adminPrincipal(), PHARMACY_ID, jobId);

    assertThat(result.get("job_id")).isEqualTo(jobId.toString());
    assertThat(result.get("overall_status")).isEqualTo("PASS");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> checks = (List<Map<String, Object>>) result.get("checks");
    assertThat(checks).hasSize(3);

    for (KycVerificationRecord record : verificationStore.findByJobId(jobId)) {
      Map<String, Object> payload = record.requestPayload();
      assertThat(payload.values()).doesNotContain("stub-not-logged");
      if (payload.containsKey("api_key")) {
        assertThat(payload.get("api_key")).isEqualTo("[REDACTED]");
      }
      if (payload.containsKey("authorization")) {
        assertThat(payload.get("authorization")).isEqualTo("[REDACTED]");
      }
      if (payload.containsKey("secret")) {
        assertThat(payload.get("secret")).isEqualTo("[REDACTED]");
      }
    }
  }

  // ─── 12. No auto-KYC job ─────────────────────────────────────────────────────

  @Test
  void noAutoKycJobReturns404() {
    assertThatThrownBy(() -> service.adminGetAutoVerifyResult(adminPrincipal(), PHARMACY_ID, null))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("NO_AUTO_KYC_JOB");
              assertThat(app.httpStatus()).isEqualTo(404);
            });
  }

  // ─── 13. Webhook callback updates verification ─────────────────────────────────

  @Test
  void webhookCallbackUpdatesVerification() throws Exception {
    UUID jobId = Ids.newId();
    UUID verificationId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, null, "SYSTEM", "PENDING", false, NOW, null));
    verificationStore.insert(
        new KycVerificationRecord(
            verificationId,
            PHARMACY_ID,
            jobId,
            "DRUG_LICENCE",
            StubDrugLicenceVerificationClient.API_PROVIDER,
            Map.of("licence_number", "DL-MH-12345", "state", "MH"),
            null,
            "PENDING",
            null,
            List.of(),
            0,
            null,
            null,
            NOW));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("provider", StubDrugLicenceVerificationClient.API_PROVIDER);
    body.put("job_id", jobId.toString());
    body.put("verification_type", "DRUG_LICENCE");
    body.put("status", "PASS");
    body.put("data", Map.of("licence_status", "ACTIVE", "expiry_date", "2028-06-30"));

    byte[] rawBody = new ObjectMapper().writeValueAsBytes(body);
    String signature = "sha256=" + AutoKycService.hmacSha256Hex(WEBHOOK_SECRET, rawBody);

    Map<String, Object> response = service.handleWebhookCallback(signature, rawBody);

    assertThat(response.get("acknowledged")).isEqualTo(true);
    assertThat(response.get("verification_id")).isEqualTo(verificationId.toString());

    KycVerificationRecord updated = verificationStore.findById(verificationId).orElseThrow();
    assertThat(updated.status()).isEqualTo("PASS");
    assertThat(updated.verifiedAt()).isEqualTo(NOW);
  }

  // ─── 14. Invalid webhook signature ───────────────────────────────────────────

  @Test
  void invalidWebhookSignatureReturns401() {
    byte[] body = "{\"provider\":\"MH_DRUG_CONTROL_API\"}".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> service.handleWebhookCallback("bad-signature", body))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("INVALID_WEBHOOK_SIGNATURE");
              assertThat(app.httpStatus()).isEqualTo(401);
            });

    assertThatThrownBy(() -> service.handleWebhookCallback(null, body))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_WEBHOOK_SIGNATURE");
  }

  // ─── 15. Compliance can read but not trigger ─────────────────────────────────

  @Test
  void complianceCanReadResultsButNotTrigger() {
    Map<String, Object> trigger =
        service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, null);
    UUID jobId = UUID.fromString((String) trigger.get("job_id"));

    MedmatePrincipal compliance =
        new MedmatePrincipal(ADMIN_ID, AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "jti");

    Map<String, Object> result = service.adminGetAutoVerifyResult(compliance, PHARMACY_ID, jobId);
    assertThat(result.get("job_id")).isEqualTo(jobId.toString());

    assertThatThrownBy(() -> service.adminTriggerAutoVerify(compliance, PHARMACY_ID, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  // ─── 16. System trigger idempotent when in progress ──────────────────────────

  @Test
  void handleAutoVerifyRequestedIdempotentWhenInProgress() {
    UUID jobId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, null, "SYSTEM", "PENDING", false, NOW, null));

    service.handleAutoVerifyRequested(PHARMACY_ID);

    assertThat(jobStore.jobs).hasSize(1);
    assertThat(jobStore.jobs.get(0).id()).isEqualTo(jobId);
  }

  // ─── 17. Process due retries count ───────────────────────────────────────────

  @Test
  void processDueRetriesReturnsCount() {
    UUID jobId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PARTIAL", false, NOW, null));
    verificationStore.insert(
        pendingRetryVerification(Ids.newId(), PHARMACY_ID, jobId, "DRUG_LICENCE", NOW));
    verificationStore.insert(
        pendingRetryVerification(Ids.newId(), PHARMACY_ID, jobId, "FSSAI", NOW));

    pharmacyStore.save(
        withDrugLicence(kycSubmittedPharmacy(PHARMACY_ID), "DL-MH-error-12345", "MH"));

    int count = retryWorker.processDueRetries();
    assertThat(count).isEqualTo(2);
  }

  @Test
  void handleAutoVerifyRequestedStartsJobWhenEnabled() {
    service.handleAutoVerifyRequested(PHARMACY_ID);
    assertThat(jobStore.jobs).hasSize(1);
  }

  @Test
  void handleAutoVerifyRequestedNoOpWhenDisabled() {
    AutoKycService disabled = newService(false);
    disabled.handleAutoVerifyRequested(PHARMACY_ID);
    assertThat(jobStore.jobs).isEmpty();
  }

  @Test
  void handleAutoVerifyRequestedNoOpWhenNotKycSubmitted() {
    pharmacyStore.save(pharmacyWithStatus(PHARMACY_ID, "PENDING_KYC"));
    service.handleAutoVerifyRequested(PHARMACY_ID);
    assertThat(jobStore.jobs).isEmpty();
  }

  @Test
  void latestAutoKycSummaryReturnsNullWhenNoJob() {
    assertThat(service.latestAutoKycSummary(PHARMACY_ID)).isNull();
  }

  @Test
  void latestAutoKycSummaryReturnsLatestJob() {
    service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, List.of("GSTIN"));
    assertThat(service.latestAutoKycSummary(PHARMACY_ID)).isNotNull();
  }

  @Test
  void webhookRejectsUnknownProvider() {
    UUID jobId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PARTIAL", false, NOW, null));
    String body =
        "{\"provider\":\"UNKNOWN\",\"job_id\":\""
            + jobId
            + "\",\"verification_type\":\"FSSAI\",\"status\":\"PASS\",\"data\":{}}";
    assertThatThrownBy(() -> service.handleWebhookCallback(sign(body), body.getBytes()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void webhookRejectsInvalidVerificationType() throws Exception {
    UUID jobId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PARTIAL", false, NOW, null));
    String body =
        "{\"provider\":\"FSSAI_PORTAL_API\",\"job_id\":\""
            + jobId
            + "\",\"verification_type\":\"GSTIN\",\"status\":\"PASS\",\"data\":{}}";
    assertThatThrownBy(() -> service.handleWebhookCallback(sign(body), body.getBytes()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void webhookAcksCompletedJobIdempotently() throws Exception {
    UUID jobId = Ids.newId();
    UUID verificationId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PASS", false, NOW, NOW));
    verificationStore.insert(
        new KycVerificationRecord(
            verificationId,
            PHARMACY_ID,
            jobId,
            "FSSAI",
            StubFssaiVerificationClient.API_PROVIDER,
            Map.of("licence_number", "11223344556677"),
            null,
            "PASS",
            null,
            List.of(),
            0,
            null,
            NOW,
            NOW));
    String body =
        "{\"provider\":\"FSSAI_PORTAL_API\",\"job_id\":\""
            + jobId
            + "\",\"verification_type\":\"FSSAI\",\"status\":\"PASS\",\"data\":{}}";
    Map<String, Object> response = service.handleWebhookCallback(sign(body), body.getBytes());
    assertThat(response.get("acknowledged")).isEqualTo(true);
    assertThat(response.get("duplicate")).isEqualTo(true);
    assertThat(response.get("verification_id")).isEqualTo(verificationId.toString());
  }

  @Test
  void processAsyncCheckIgnoresTerminalVerification() {
    UUID jobId = Ids.newId();
    UUID verificationId = Ids.newId();
    verificationStore.insert(
        new KycVerificationRecord(
            verificationId,
            PHARMACY_ID,
            jobId,
            "FSSAI",
            StubFssaiVerificationClient.API_PROVIDER,
            Map.of("licence_number", "11223344556677"),
            null,
            "PASS",
            null,
            List.of(),
            0,
            null,
            NOW,
            NOW));
    service.processAsyncCheck(verificationId);
    assertThat(verificationStore.findById(verificationId).orElseThrow().status()).isEqualTo("PASS");
  }

  @Test
  void processAsyncCheckNotFound() {
    assertThatThrownBy(() -> service.processAsyncCheck(Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VERIFICATION_NOT_FOUND");
  }

  @Test
  void webhookVerificationRecordNotFound() throws Exception {
    UUID jobId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PARTIAL", false, NOW, null));
    String body =
        "{\"provider\":\"FSSAI_PORTAL_API\",\"job_id\":\""
            + jobId
            + "\",\"verification_type\":\"FSSAI\",\"status\":\"PASS\",\"data\":{}}";
    assertThatThrownBy(() -> service.handleWebhookCallback(sign(body), body.getBytes()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("JOB_NOT_FOUND");
  }

  @Test
  void resolveChecksIgnoresBlankEntries() {
    List<String> checks = new ArrayList<>();
    checks.add("");
    checks.add("  ");
    checks.add("GSTIN");
    checks.add(null);
    Map<String, Object> data =
        service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, checks);
    assertThat(data.get("checks_triggered")).asList().containsExactly("GSTIN");
  }

  @Test
  void adminTriggerRequiresAuth() {
    assertThatThrownBy(() -> service.adminTriggerAutoVerify(null, PHARMACY_ID, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void adminGetResultRequiresAuth() {
    assertThatThrownBy(() -> service.adminGetAutoVerifyResult(null, PHARMACY_ID, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void adminGetResultForbiddenForCustomer() {
    MedmatePrincipal customer =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.adminGetAutoVerifyResult(customer, PHARMACY_ID, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void webhookRejectsInvalidJson() {
    String body = "not-json";
    assertThatThrownBy(() -> service.handleWebhookCallback(sign(body), body.getBytes()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void webhookRejectsMissingFields() throws Exception {
    assertThatThrownBy(
            () -> service.handleWebhookCallback(sign("{}"), "{}".getBytes(StandardCharsets.UTF_8)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void resolveChecksAllBlankDefaultsToAllChecks() {
    List<String> checks = new ArrayList<>();
    checks.add("");
    checks.add(null);
    Map<String, Object> data =
        service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, checks);
    assertThat(data.get("checks_triggered")).asList().hasSize(3);
  }

  @Test
  void webhookInvalidJobIdUuid() throws Exception {
    String body =
        "{\"provider\":\"FSSAI_PORTAL_API\",\"job_id\":\"not-a-uuid\",\"verification_type\":\"FSSAI\",\"status\":\"PASS\",\"data\":{}}";
    assertThatThrownBy(() -> service.handleWebhookCallback(sign(body), body.getBytes()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void autoActivateUsesDefaultZoneWhenPincodeUnmapped() {
    PharmacyRecord noPin =
        new PharmacyRecord(
            PHARMACY_ID,
            "Sharma Medical Store",
            "Sharma Medical Store",
            "Priya Sharma",
            "+919876543210",
            "owner@test.in",
            "hash",
            "PHARMACY",
            null,
            "KYC_SUBMITTED",
            "FREE",
            null,
            "27AABCS1429B1ZB",
            "DL-MH-12345",
            "MH",
            "11223344556677",
            "AABCS1429B",
            new BigDecimal("8.00"),
            null,
            false,
            true,
            true,
            "Bengaluru",
            "FREE",
            NOW,
            NOW,
            NOW);
    pharmacyStore.save(noPin);
    service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, null);
    assertThat(pharmacyStore.findById(PHARMACY_ID).orElseThrow().status()).isEqualTo("ACTIVE");
  }

  @Test
  void webhookRejectsUnknownStatus() throws Exception {
    UUID jobId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PARTIAL", false, NOW, null));
    verificationStore.insert(
        new KycVerificationRecord(
            Ids.newId(),
            PHARMACY_ID,
            jobId,
            "FSSAI",
            StubFssaiVerificationClient.API_PROVIDER,
            Map.of("licence_number", "11223344556677"),
            null,
            "PENDING",
            null,
            List.of(),
            0,
            null,
            null,
            NOW));
    String body =
        "{\"provider\":\"FSSAI_PORTAL_API\",\"job_id\":\""
            + jobId
            + "\",\"verification_type\":\"FSSAI\",\"status\":\"WEIRD\",\"data\":{\"licence_number\":\"11223344556677\"}}";
    assertThatThrownBy(() -> service.handleWebhookCallback(sign(body), body.getBytes()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void webhookWarnStatusNormalizesToPass() throws Exception {
    UUID jobId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PARTIAL", false, NOW, null));
    verificationStore.insert(
        new KycVerificationRecord(
            Ids.newId(),
            PHARMACY_ID,
            jobId,
            "FSSAI",
            StubFssaiVerificationClient.API_PROVIDER,
            Map.of("licence_number", "11223344556677"),
            null,
            "PENDING",
            null,
            List.of(),
            0,
            null,
            null,
            NOW));
    String body =
        "{\"provider\":\"FSSAI_PORTAL_API\",\"job_id\":\""
            + jobId
            + "\",\"verification_type\":\"FSSAI\",\"status\":\"WARN\",\"data\":{\"licence_number\":\"11223344556677\"}}";
    service.handleWebhookCallback(sign(body), body.getBytes());
    assertThat(verificationStore.findByJobAndType(jobId, "FSSAI").orElseThrow().status())
        .isEqualTo("PASS");
  }

  @Test
  void reevaluateJobWithNoChecksIsNoOp() throws Exception {
    UUID jobId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PENDING", false, NOW, null));
    java.lang.reflect.Method reevaluate =
        AutoKycService.class.getDeclaredMethod("reevaluateJob", UUID.class);
    reevaluate.setAccessible(true);
    reevaluate.invoke(service, jobId);
    assertThat(jobStore.findById(jobId).orElseThrow().overallStatus()).isEqualTo("PENDING");
  }

  @Test
  void rateLimitExceededOnTrigger() {
    RateLimiter limiter = mock(RateLimiter.class);
    when(limiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(false);
    when(limiter.secondsUntilAvailable(any(), anyInt(), anyInt())).thenReturn(30);
    AutoKycService limited =
        new AutoKycService(
            pharmacyStore,
            jobStore,
            verificationStore,
            pincodeZoneStore,
            new StubGstinVerificationClient(),
            new StubDrugLicenceVerificationClient(),
            new StubFssaiVerificationClient(),
            outbox,
            limiter,
            clock,
            true,
            WEBHOOK_SECRET);
    assertThatThrownBy(() -> limited.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  private String sign(String body) {
    return "sha256="
        + AutoKycService.hmacSha256Hex(WEBHOOK_SECRET, body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void gstinTransientErrorRetriesViaWorker() {
    UUID jobId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PARTIAL", false, NOW, null));
    UUID verificationId = Ids.newId();
    verificationStore.insert(
        pendingRetryVerification(verificationId, PHARMACY_ID, jobId, "GSTIN", NOW));
    pharmacyStore.save(withGstin(kycSubmittedPharmacy(PHARMACY_ID), "27errorAABCS1429B1ZB"));

    assertThat(retryWorker.processDueRetries()).isEqualTo(1);

    KycVerificationRecord after = verificationStore.findById(verificationId).orElseThrow();
    assertThat(after.status()).isEqualTo("ERROR");
    assertThat(after.retryCount()).isEqualTo(2);
    assertThat(after.nextRetryAt()).isEqualTo(NOW.plusSeconds(30));
  }

  @Test
  void processStaleAsyncChecksTimesOutPendingVerification() {
    UUID jobId = Ids.newId();
    UUID verificationId = Ids.newId();
    Instant staleCreatedAt = NOW.minus(AutoKycService.ASYNC_CHECK_TIMEOUT).minusSeconds(1);
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PARTIAL", false, NOW, null));
    verificationStore.insert(
        new KycVerificationRecord(
            verificationId,
            PHARMACY_ID,
            jobId,
            "FSSAI",
            StubFssaiVerificationClient.API_PROVIDER,
            Map.of("licence_number", "11223344556677"),
            null,
            "PENDING",
            null,
            List.of(),
            0,
            null,
            null,
            staleCreatedAt));

    assertThat(service.processStaleAsyncChecks()).isEqualTo(1);
    assertThat(verificationStore.findById(verificationId).orElseThrow().status())
        .isEqualTo("ERROR");
    assertThat(verificationStore.findById(verificationId).orElseThrow().details())
        .containsEntry("reason", "ASYNC_CHECK_TIMEOUT");
  }

  @Test
  void validateWebhookSecretRejectsLocalDefaultInDeployedProfile() {
    assertThatThrownBy(
            () ->
                AutoKycService.validateWebhookSecretForDeployedProfile(
                    "local-kyc-webhook-secret", true))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void validateWebhookSecretAllowsLocalProfile() {
    AutoKycService.validateWebhookSecretForDeployedProfile("local-kyc-webhook-secret", false);
    AutoKycService.validateWebhookSecretForDeployedProfile("", false);
  }

  @Test
  void validateWebhookSecretRejectsBlankInDeployedProfile() {
    assertThatThrownBy(() -> AutoKycService.validateWebhookSecretForDeployedProfile("", true))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> AutoKycService.validateWebhookSecretForDeployedProfile(null, true))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void validateWebhookSecretAcceptsInjectedDeployedSecret() {
    AutoKycService.validateWebhookSecretForDeployedProfile("prod-kyc-hmac-secret", true);
  }

  @Test
  void gstinRetryUsesPharmacyNameWhenBusinessNameNull() {
    UUID jobId = Ids.newId();
    UUID verificationId = Ids.newId();
    PharmacyRecord base = kycSubmittedPharmacy(PHARMACY_ID);
    PharmacyRecord noBusinessName =
        new PharmacyRecord(
            base.id(),
            "Fallback Store Name",
            null,
            base.ownerName(),
            base.phone(),
            base.email(),
            base.passwordHash(),
            base.businessType(),
            base.address(),
            base.status(),
            base.plan(),
            base.planExpiresAt(),
            "27errorAABCS1429B1ZB",
            base.drugLicenceNumber(),
            base.licenceStateCode(),
            base.fssaiNumber(),
            base.panNumber(),
            base.commissionPct(),
            base.zoneId(),
            base.online(),
            base.emailVerified(),
            base.canReapply(),
            base.city(),
            base.subscriptionPlan(),
            base.createdAt(),
            base.updatedAt(),
            base.kycSubmittedAt());
    pharmacyStore.save(noBusinessName);
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PARTIAL", false, NOW, null));
    verificationStore.insert(
        pendingRetryVerification(verificationId, PHARMACY_ID, jobId, "GSTIN", NOW));

    retryWorker.processDueRetries();

    assertThat(verificationStore.findById(verificationId).orElseThrow().retryCount()).isEqualTo(2);
  }

  @Test
  void webhookAcksTerminalVerificationWhenJobStillOpen() throws Exception {
    UUID jobId = Ids.newId();
    UUID verificationId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PARTIAL", false, NOW, null));
    verificationStore.insert(
        new KycVerificationRecord(
            verificationId,
            PHARMACY_ID,
            jobId,
            "FSSAI",
            StubFssaiVerificationClient.API_PROVIDER,
            Map.of("licence_number", "11223344556677"),
            null,
            "PASS",
            Map.of("licence_status", "ACTIVE"),
            List.of(),
            0,
            null,
            NOW,
            NOW));
    String body =
        "{\"provider\":\"FSSAI_PORTAL_API\",\"job_id\":\""
            + jobId
            + "\",\"verification_type\":\"FSSAI\",\"status\":\"PASS\",\"data\":{}}";
    Map<String, Object> response = service.handleWebhookCallback(sign(body), body.getBytes());
    assertThat(response.get("duplicate")).isEqualTo(true);
  }

  // ─── Service factory & helpers ───────────────────────────────────────────────

  private AutoKycService newService(boolean autoVerificationEnabled) {
    AutoKycService svc =
        new AutoKycService(
            pharmacyStore,
            jobStore,
            verificationStore,
            pincodeZoneStore,
            new StubGstinVerificationClient(),
            new StubDrugLicenceVerificationClient(),
            new StubFssaiVerificationClient(),
            outbox,
            new AllowAllRateLimiter(),
            clock,
            autoVerificationEnabled,
            WEBHOOK_SECRET);
    svc.setSelf(svc);
    return svc;
  }

  private MedmatePrincipal adminPrincipal() {
    return new MedmatePrincipal(ADMIN_ID, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "jti");
  }

  static PharmacyRecord kycSubmittedPharmacy(UUID id) {
    return new PharmacyRecord(
        id,
        "Sharma Medical Store",
        "Sharma Medical Store",
        "Priya Sharma",
        "+919876543210",
        "owner@test.in",
        "hash",
        "PHARMACY",
        Map.of("line1", "123 MG Road", "city", "Bengaluru", "pincode", "560001"),
        "KYC_SUBMITTED",
        "FREE",
        null,
        "27AABCS1429B1ZB",
        "DL-MH-12345",
        "MH",
        "11223344556677",
        "AABCS1429B",
        new BigDecimal("8.00"),
        null,
        false,
        true,
        true,
        "Bengaluru",
        "FREE",
        NOW,
        NOW,
        NOW);
  }

  static PharmacyRecord pharmacyWithStatus(UUID id, String status) {
    PharmacyRecord base = kycSubmittedPharmacy(id);
    return new PharmacyRecord(
        base.id(),
        base.name(),
        base.businessName(),
        base.ownerName(),
        base.phone(),
        base.email(),
        base.passwordHash(),
        base.businessType(),
        base.address(),
        status,
        base.plan(),
        base.planExpiresAt(),
        base.gstin(),
        base.drugLicenceNumber(),
        base.licenceStateCode(),
        base.fssaiNumber(),
        base.panNumber(),
        base.commissionPct(),
        base.zoneId(),
        base.online(),
        base.emailVerified(),
        base.canReapply(),
        base.city(),
        base.subscriptionPlan(),
        base.createdAt(),
        base.updatedAt(),
        base.kycSubmittedAt());
  }

  static KycVerificationRecord pendingRetryVerification(
      UUID id, UUID pharmacyId, UUID jobId, String type, Instant dueAt) {
    return new KycVerificationRecord(
        id,
        pharmacyId,
        jobId,
        type,
        StubDrugLicenceVerificationClient.API_PROVIDER,
        Map.of("licence_number", "DL-MH-error-12345", "state", "MH"),
        null,
        "ERROR",
        null,
        List.of(),
        1,
        dueAt,
        null,
        NOW);
  }

  static PharmacyRecord withGstin(PharmacyRecord p, String gstin) {
    return copyPharmacy(p, gstin, p.drugLicenceNumber(), p.licenceStateCode(), p.fssaiNumber());
  }

  static PharmacyRecord withDrugLicence(PharmacyRecord p, String licence, String state) {
    return copyPharmacy(p, p.gstin(), licence, state, p.fssaiNumber());
  }

  static PharmacyRecord withFssai(PharmacyRecord p, String fssai) {
    return copyPharmacy(p, p.gstin(), p.drugLicenceNumber(), p.licenceStateCode(), fssai);
  }

  static PharmacyRecord copyPharmacy(
      PharmacyRecord p, String gstin, String licence, String state, String fssai) {
    return new PharmacyRecord(
        p.id(),
        p.name(),
        p.businessName(),
        p.ownerName(),
        p.phone(),
        p.email(),
        p.passwordHash(),
        p.businessType(),
        p.address(),
        p.status(),
        p.plan(),
        p.planExpiresAt(),
        gstin,
        licence,
        state,
        fssai,
        p.panNumber(),
        p.commissionPct(),
        p.zoneId(),
        p.online(),
        p.emailVerified(),
        p.canReapply(),
        p.city(),
        p.subscriptionPlan(),
        p.createdAt(),
        p.updatedAt(),
        p.kycSubmittedAt());
  }

  // ─── Fake implementations ────────────────────────────────────────────────────

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

  static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant start) {
      this.instant = start;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  static final class FakePharmacyStore implements PharmacyRegistrationStore {
    private final Map<UUID, PharmacyRecord> store = new HashMap<>();

    void save(PharmacyRecord record) {
      store.put(record.id(), record);
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
        UUID pharmacyId, String status, Instant kycSubmittedAt, Instant updatedAt) {}

    @Override
    public void activateAfterAutoKyc(UUID pharmacyId, UUID zoneId, Instant at) {
      PharmacyRecord p = store.get(pharmacyId);
      if (p == null) {
        return;
      }
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
              "ACTIVE",
              p.plan(),
              p.planExpiresAt(),
              p.gstin(),
              p.drugLicenceNumber(),
              p.licenceStateCode(),
              p.fssaiNumber(),
              p.panNumber(),
              p.commissionPct(),
              zoneId,
              true,
              p.emailVerified(),
              p.canReapply(),
              p.city(),
              p.subscriptionPlan(),
              p.createdAt(),
              at,
              p.kycSubmittedAt()));
    }
  }

  static final class FakeAutoKycJobStore implements AutoKycJobStore {
    final List<AutoKycJobRecord> jobs = new ArrayList<>();

    @Override
    public void insert(AutoKycJobRecord job) {
      jobs.add(job);
    }

    @Override
    public Optional<AutoKycJobRecord> findById(UUID jobId) {
      return jobs.stream().filter(j -> j.id().equals(jobId)).findFirst();
    }

    @Override
    public Optional<AutoKycJobRecord> findLatestByPharmacy(UUID pharmacyId) {
      return jobs.stream()
          .filter(j -> j.pharmacyId().equals(pharmacyId))
          .max(java.util.Comparator.comparing(AutoKycJobRecord::triggeredAt));
    }

    @Override
    public Optional<AutoKycJobRecord> findInProgressByPharmacy(UUID pharmacyId) {
      return jobs.stream()
          .filter(
              j ->
                  j.pharmacyId().equals(pharmacyId)
                      && ("PENDING".equals(j.overallStatus())
                          || "PARTIAL".equals(j.overallStatus())))
          .max(java.util.Comparator.comparing(AutoKycJobRecord::triggeredAt));
    }

    @Override
    public void updateOverallStatus(UUID jobId, String overallStatus, Instant completedAt) {
      jobs.replaceAll(
          j ->
              j.id().equals(jobId)
                  ? new AutoKycJobRecord(
                      j.id(),
                      j.pharmacyId(),
                      j.triggeredBy(),
                      j.triggerSource(),
                      overallStatus,
                      j.autoActivated(),
                      j.triggeredAt(),
                      completedAt)
                  : j);
    }

    @Override
    public void markAutoActivated(UUID jobId, Instant completedAt) {
      jobs.replaceAll(
          j ->
              j.id().equals(jobId)
                  ? new AutoKycJobRecord(
                      j.id(),
                      j.pharmacyId(),
                      j.triggeredBy(),
                      j.triggerSource(),
                      "PASS",
                      true,
                      j.triggeredAt(),
                      completedAt)
                  : j);
    }
  }

  static final class FakeKycVerificationStore implements KycVerificationStore {
    final List<KycVerificationRecord> records = new ArrayList<>();

    @Override
    public void insert(KycVerificationRecord record) {
      records.add(record);
    }

    @Override
    public Optional<KycVerificationRecord> findById(UUID id) {
      return records.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    @Override
    public List<KycVerificationRecord> findByJobId(UUID jobId) {
      return records.stream().filter(r -> r.jobId().equals(jobId)).toList();
    }

    @Override
    public Optional<KycVerificationRecord> findByJobAndType(UUID jobId, String verificationType) {
      return records.stream()
          .filter(r -> r.jobId().equals(jobId) && r.verificationType().equals(verificationType))
          .findFirst();
    }

    @Override
    public void updateResult(
        UUID id,
        String status,
        Map<String, Object> responsePayload,
        Map<String, Object> details,
        List<Map<String, Object>> adminFlags,
        int retryCount,
        Instant nextRetryAt,
        Instant verifiedAt) {
      records.replaceAll(
          r ->
              r.id().equals(id)
                  ? new KycVerificationRecord(
                      r.id(),
                      r.pharmacyId(),
                      r.jobId(),
                      r.verificationType(),
                      r.apiProvider(),
                      r.requestPayload(),
                      responsePayload,
                      status,
                      details,
                      adminFlags,
                      retryCount,
                      nextRetryAt,
                      verifiedAt,
                      r.createdAt())
                  : r);
    }

    @Override
    public List<KycVerificationRecord> findDueRetries(Instant now, int limit) {
      return records.stream()
          .filter(
              r ->
                  "ERROR".equals(r.status())
                      && r.nextRetryAt() != null
                      && !r.nextRetryAt().isAfter(now))
          .limit(limit)
          .toList();
    }

    @Override
    public List<KycVerificationRecord> findStalePending(Instant createdBefore, int limit) {
      return records.stream()
          .filter(r -> "PENDING".equals(r.status()) && !r.createdAt().isAfter(createdBefore))
          .limit(limit)
          .toList();
    }
  }

  static final class FakePincodeZoneStore implements PincodeZoneStore {
    final Map<String, UUID> zoneByPincode = new HashMap<>();

    @Override
    public Optional<UUID> findZoneIdByPincode(String pincode) {
      return Optional.ofNullable(zoneByPincode.get(pincode));
    }
  }
}

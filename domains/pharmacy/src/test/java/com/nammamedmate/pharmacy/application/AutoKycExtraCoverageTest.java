package com.nammamedmate.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.adapter.out.kyc.StubDrugLicenceVerificationClient;
import com.nammamedmate.pharmacy.adapter.out.kyc.StubFssaiVerificationClient;
import com.nammamedmate.pharmacy.adapter.out.kyc.StubGstinVerificationClient;
import com.nammamedmate.pharmacy.application.port.out.AutoKycJobStore.AutoKycJobRecord;
import com.nammamedmate.pharmacy.application.port.out.KycGovernmentApiPort.KycCheckResult;
import com.nammamedmate.pharmacy.application.port.out.KycVerificationStore.KycVerificationRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore.PharmacyRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Branch and edge coverage for {@link AutoKycService} not covered by acceptance tests. */
class AutoKycExtraCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");
  private static final String WEBHOOK_SECRET = "test-webhook-secret";
  private static final UUID PHARMACY_ID = Ids.newId();
  private static final UUID ADMIN_ID = Ids.newId();

  private AutoKycServiceTest.FakePharmacyStore pharmacyStore;
  private AutoKycServiceTest.FakeAutoKycJobStore jobStore;
  private AutoKycServiceTest.FakeKycVerificationStore verificationStore;
  private AutoKycServiceTest.FakePincodeZoneStore pincodeZoneStore;
  private AutoKycService service;

  @BeforeEach
  void setUp() {
    pharmacyStore = new AutoKycServiceTest.FakePharmacyStore();
    jobStore = new AutoKycServiceTest.FakeAutoKycJobStore();
    verificationStore = new AutoKycServiceTest.FakeKycVerificationStore();
    pincodeZoneStore = new AutoKycServiceTest.FakePincodeZoneStore();
    InMemoryOutboxStore outboxStore = new InMemoryOutboxStore();
    OutboxPublisher outbox = new OutboxPublisher(outboxStore, new ObjectMapper());
    pharmacyStore.save(AutoKycServiceTest.kycSubmittedPharmacy(PHARMACY_ID));
    pincodeZoneStore.zoneByPincode.put("560001", Ids.newId());
    service =
        new AutoKycService(
            pharmacyStore,
            jobStore,
            verificationStore,
            pincodeZoneStore,
            new StubGstinVerificationClient(),
            new StubDrugLicenceVerificationClient(),
            new StubFssaiVerificationClient(),
            outbox,
            new AutoKycServiceTest.AllowAllRateLimiter(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            true,
            WEBHOOK_SECRET);
  }

  @Test
  void hmacSha256HexCoversExceptionPath() {
    assertThatThrownBy(() -> AutoKycService.hmacSha256Hex(null, new byte[0]))
        .isInstanceOf(IllegalStateException.class);
    assertThat(AutoKycService.hmacSha256Hex("secret", "body".getBytes(StandardCharsets.UTF_8)))
        .isNotBlank();
  }

  @Test
  void webhookRejectsSameLengthWrongSignature() throws Exception {
    UUID jobId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PENDING", false, NOW, null));
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
            + "\",\"verification_type\":\"FSSAI\",\"status\":\"PASS\",\"data\":{}}";
    String valid = AutoKycService.hmacSha256Hex(WEBHOOK_SECRET, body.getBytes());
    char[] chars = valid.toCharArray();
    chars[0] = chars[0] == 'a' ? 'b' : 'a';
    String bad = "sha256=" + new String(chars);
    assertThatThrownBy(() -> service.handleWebhookCallback(bad, body.getBytes()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_WEBHOOK_SIGNATURE");
  }

  @Test
  void webhookRejectsBlankSignature() {
    assertThatThrownBy(
            () ->
                service.handleWebhookCallback(
                    "   ", "{\"provider\":\"FSSAI_PORTAL_API\"}".getBytes()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_WEBHOOK_SIGNATURE");
  }

  @Test
  void handleAutoVerifyRequestedNoOpWhenPharmacyMissing() {
    service.handleAutoVerifyRequested(Ids.newId());
    assertThat(jobStore.jobs).isEmpty();
  }

  @Test
  void adminGetResultPharmacyNotFound() {
    assertThatThrownBy(() -> service.adminGetAutoVerifyResult(adminPrincipal(), Ids.newId(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
  }

  @Test
  void webhookJobNotFound() throws Exception {
    UUID jobId = Ids.newId();
    String body =
        "{\"provider\":\"FSSAI_PORTAL_API\",\"job_id\":\""
            + jobId
            + "\",\"verification_type\":\"FSSAI\",\"status\":\"PASS\",\"data\":{}}";
    assertThatThrownBy(() -> service.handleWebhookCallback(sign(body), body.getBytes()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("JOB_NOT_FOUND");
  }

  @Test
  void processAsyncCheckPharmacyNotFound() {
    UUID missingPharmacyId = Ids.newId();
    UUID jobId = Ids.newId();
    UUID verificationId = Ids.newId();
    verificationStore.insert(
        new KycVerificationRecord(
            verificationId,
            missingPharmacyId,
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
    assertThatThrownBy(() -> service.processAsyncCheck(verificationId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
  }

  @Test
  void processAsyncCheckUnknownVerificationType() {
    UUID jobId = Ids.newId();
    UUID verificationId = Ids.newId();
    verificationStore.insert(
        new KycVerificationRecord(
            verificationId,
            PHARMACY_ID,
            jobId,
            "UNKNOWN",
            "TEST",
            Map.of(),
            null,
            "PENDING",
            null,
            List.of(),
            0,
            null,
            null,
            NOW));
    assertThatThrownBy(() -> service.processAsyncCheck(verificationId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CHECK_TYPE");
  }

  @Test
  void triggerAsyncOnlyCheckWithoutGstin() {
    Map<String, Object> result =
        service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, List.of("FSSAI"));
    assertThat(result).doesNotContainKey("gstin_result");
    assertThat(result).containsKey("fssai_result");
    assertThat(result).doesNotContainKey("drug_licence_result");
  }

  @Test
  void gstinCheckUsesPharmacyNameWhenBusinessNameNull() {
    PharmacyRecord base = AutoKycServiceTest.kycSubmittedPharmacy(PHARMACY_ID);
    pharmacyStore.save(
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
            base.kycSubmittedAt()));
    Map<String, Object> result =
        service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, List.of("GSTIN"));
    @SuppressWarnings("unchecked")
    Map<String, Object> gstin = (Map<String, Object>) result.get("gstin_result");
    assertThat(gstin.get("status")).isEqualTo("PASS");
  }

  @Test
  void webhookWithNullDataUsesEmptyDetails() throws Exception {
    UUID jobId = Ids.newId();
    UUID verificationId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PENDING", false, NOW, null));
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
            NOW));
    String body =
        "{\"provider\":\"FSSAI_PORTAL_API\",\"job_id\":\""
            + jobId
            + "\",\"verification_type\":\"FSSAI\",\"status\":\"PASS\"}";
    service.handleWebhookCallback(sign(body), body.getBytes());
    assertThat(verificationStore.findById(verificationId).orElseThrow().status()).isEqualTo("PASS");
  }

  @Test
  void webhookDrugLicencePassWithNonStringExpirySkipsExpiryCheck() throws Exception {
    UUID jobId = Ids.newId();
    UUID verificationId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PENDING", false, NOW, null));
    verificationStore.insert(
        new KycVerificationRecord(
            verificationId,
            PHARMACY_ID,
            jobId,
            "DRUG_LICENCE",
            StubDrugLicenceVerificationClient.API_PROVIDER,
            Map.of("licence_number", "DL-MH-1"),
            null,
            "PENDING",
            null,
            List.of(),
            0,
            null,
            null,
            NOW));
    String body =
        "{\"provider\":\"MH_DRUG_CONTROL_API\",\"job_id\":\""
            + jobId
            + "\",\"verification_type\":\"DRUG_LICENCE\",\"status\":\"PASS\","
            + "\"data\":{\"expiry_date\":12345}}";
    service.handleWebhookCallback(sign(body), body.getBytes());
    assertThat(verificationStore.findById(verificationId).orElseThrow().status()).isEqualTo("PASS");
  }

  @Test
  void adminSuperCanTriggerAndRead() {
    MedmatePrincipal superAdmin =
        new MedmatePrincipal(ADMIN_ID, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    Map<String, Object> trigger =
        service.adminTriggerAutoVerify(superAdmin, PHARMACY_ID, List.of("GSTIN"));
    UUID jobId = UUID.fromString((String) trigger.get("job_id"));
    assertThat(service.adminGetAutoVerifyResult(superAdmin, PHARMACY_ID, jobId))
        .containsEntry("job_id", jobId.toString());
  }

  @Test
  void buildJobResultIncludesCompletedAtWhenPresent() {
    UUID jobId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PASS", true, NOW, NOW));
    verificationStore.insert(
        new KycVerificationRecord(
            Ids.newId(),
            PHARMACY_ID,
            jobId,
            "GSTIN",
            StubGstinVerificationClient.API_PROVIDER,
            Map.of("gstin", "27TEST"),
            Map.of("ok", true),
            "PASS",
            Map.of("gstin", "27TEST"),
            List.of(),
            0,
            null,
            NOW,
            NOW));
    Map<String, Object> result =
        service.adminGetAutoVerifyResult(adminPrincipal(), PHARMACY_ID, jobId);
    assertThat(result.get("completed_at")).isEqualTo(NOW.toString());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> checks = (List<Map<String, Object>>) result.get("checks");
    assertThat(checks.getFirst().get("checked_at")).isEqualTo(NOW.toString());
  }

  @Test
  void nonTransientErrorMarksTerminalWithoutRetry() {
    AutoKycService custom =
        new AutoKycService(
            pharmacyStore,
            jobStore,
            verificationStore,
            pincodeZoneStore,
            new StubGstinVerificationClient(),
            (licence, state) ->
                new KycCheckResult("ERROR", "TEST", Map.of(), Map.of(), Map.of(), List.of(), false),
            new StubFssaiVerificationClient(),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            new AutoKycServiceTest.AllowAllRateLimiter(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            true,
            WEBHOOK_SECRET);
    UUID jobId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PENDING", false, NOW, null));
    UUID verificationId = Ids.newId();
    verificationStore.insert(
        new KycVerificationRecord(
            verificationId,
            PHARMACY_ID,
            jobId,
            "DRUG_LICENCE",
            "TEST",
            Map.of(),
            null,
            "PENDING",
            null,
            List.of(),
            0,
            null,
            null,
            NOW));
    custom.processAsyncCheck(verificationId);
    KycVerificationRecord updated = verificationStore.findById(verificationId).orElseThrow();
    assertThat(updated.status()).isEqualTo("ERROR");
    assertThat(updated.verifiedAt()).isEqualTo(NOW);
    assertThat(updated.nextRetryAt()).isNull();
  }

  @Test
  void normalizeStatusNullBecomesError() throws Exception {
    Method normalize =
        AutoKycService.class.getDeclaredMethod(
            "normalizeStatus", String.class, KycCheckResult.class);
    normalize.setAccessible(true);
    KycCheckResult nullStatus =
        new KycCheckResult(null, "P", Map.of(), Map.of(), Map.of(), List.of(), false);
    assertThat(normalize.invoke(null, "GSTIN", nullStatus)).isEqualTo("ERROR");
  }

  @Test
  void computeOverallStatusCoversPendingPartialAndErrorPaths() throws Exception {
    Method compute = AutoKycService.class.getDeclaredMethod("computeOverallStatus", List.class);
    compute.setAccessible(true);

    UUID jobId = Ids.newId();
    KycVerificationRecord pending =
        new KycVerificationRecord(
            Ids.newId(),
            PHARMACY_ID,
            jobId,
            "FSSAI",
            "P",
            Map.of(),
            null,
            "PENDING",
            null,
            List.of(),
            0,
            null,
            null,
            NOW);
    assertThat(compute.invoke(null, List.of(pending))).isEqualTo("PENDING");

    KycVerificationRecord pendingWithFail =
        new KycVerificationRecord(
            Ids.newId(),
            PHARMACY_ID,
            jobId,
            "GSTIN",
            "P",
            Map.of(),
            null,
            "FAIL",
            null,
            List.of(),
            0,
            null,
            NOW,
            NOW);
    assertThat(compute.invoke(null, List.of(pending, pendingWithFail))).isEqualTo("PARTIAL");

    KycVerificationRecord retrying =
        new KycVerificationRecord(
            Ids.newId(),
            PHARMACY_ID,
            jobId,
            "FSSAI",
            "P",
            Map.of(),
            null,
            "ERROR",
            null,
            List.of(),
            1,
            NOW.plusSeconds(60),
            null,
            NOW);
    assertThat(compute.invoke(null, List.of(retrying))).isEqualTo("PENDING");

    KycVerificationRecord terminalError =
        new KycVerificationRecord(
            Ids.newId(),
            PHARMACY_ID,
            jobId,
            "FSSAI",
            "P",
            Map.of(),
            null,
            "ERROR",
            null,
            List.of(),
            3,
            null,
            NOW,
            NOW);
    assertThat(compute.invoke(null, List.of(terminalError))).isEqualTo("ERROR");

    KycVerificationRecord weird =
        new KycVerificationRecord(
            Ids.newId(),
            PHARMACY_ID,
            jobId,
            "GSTIN",
            "P",
            Map.of(),
            null,
            "WEIRD",
            null,
            List.of(),
            0,
            null,
            NOW,
            NOW);
    assertThat(compute.invoke(null, List.of(weird))).isEqualTo("PARTIAL");
  }

  @Test
  void nullWebhookSecretConstructor() {
    AutoKycService withNullSecret =
        new AutoKycService(
            pharmacyStore,
            jobStore,
            verificationStore,
            pincodeZoneStore,
            new StubGstinVerificationClient(),
            new StubDrugLicenceVerificationClient(),
            new StubFssaiVerificationClient(),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            new AutoKycServiceTest.AllowAllRateLimiter(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            true,
            null);
    assertThat(withNullSecret).isNotNull();
  }

  @Test
  void resolveChecksEmptyListDefaultsToAll() {
    Map<String, Object> result =
        service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, List.of());
    assertThat(result.get("checks_triggered")).asList().hasSize(3);
  }

  @Test
  void webhookMissingRequiredFieldReturns400() throws Exception {
    String body = "{\"provider\":\"FSSAI_PORTAL_API\",\"job_id\":\"" + Ids.newId() + "\"}";
    assertThatThrownBy(() -> service.handleWebhookCallback(sign(body), body.getBytes()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void adminTriggerPharmacyNotFoundDuringTrigger() {
    assertThatThrownBy(() -> service.adminTriggerAutoVerify(adminPrincipal(), Ids.newId(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
  }

  @Test
  void triggerDrugLicenceOnlyCheck() {
    Map<String, Object> result =
        service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, List.of("DRUG_LICENCE"));
    assertThat(result).containsKey("drug_licence_result");
    assertThat(result).doesNotContainKey("gstin_result");
  }

  @Test
  void buildJobResultNullCompletedAndCheckedAt() {
    UUID jobId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PENDING", false, NOW, null));
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
    Map<String, Object> result =
        service.adminGetAutoVerifyResult(adminPrincipal(), PHARMACY_ID, jobId);
    assertThat(result.get("completed_at")).isNull();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> checks = (List<Map<String, Object>>) result.get("checks");
    assertThat(checks.getFirst().get("checked_at")).isNull();
  }

  @Test
  void webhookNullFieldValueReturns400() throws Exception {
    String body =
        "{\"provider\":\"FSSAI_PORTAL_API\",\"job_id\":\""
            + Ids.newId()
            + "\",\"verification_type\":null,\"status\":\"PASS\"}";
    assertThatThrownBy(() -> service.handleWebhookCallback(sign(body), body.getBytes()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void autoActivatePharmacyNotFound() throws Exception {
    UUID jobId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PENDING", false, NOW, null));
    java.lang.reflect.Field storeField =
        AutoKycServiceTest.FakePharmacyStore.class.getDeclaredField("store");
    storeField.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.Map<UUID, PharmacyRecord> storeMap =
        (java.util.Map<UUID, PharmacyRecord>) storeField.get(pharmacyStore);
    storeMap.remove(PHARMACY_ID);
    Method autoActivate =
        AutoKycService.class.getDeclaredMethod("autoActivate", UUID.class, UUID.class);
    autoActivate.setAccessible(true);
    assertThatThrownBy(() -> autoActivate.invoke(service, PHARMACY_ID, jobId))
        .hasCauseInstanceOf(AppException.class)
        .satisfies(
            ex ->
                assertThat(((AppException) ex.getCause()).code()).isEqualTo("PHARMACY_NOT_FOUND"));
  }

  @Test
  void computeOverallStatusPassAndWarnDoNotClearAllPass() throws Exception {
    Method compute = AutoKycService.class.getDeclaredMethod("computeOverallStatus", List.class);
    compute.setAccessible(true);
    UUID jobId = Ids.newId();
    KycVerificationRecord pass =
        new KycVerificationRecord(
            Ids.newId(),
            PHARMACY_ID,
            jobId,
            "GSTIN",
            "P",
            Map.of(),
            null,
            "PASS",
            null,
            List.of(),
            0,
            null,
            NOW,
            NOW);
    KycVerificationRecord warn =
        new KycVerificationRecord(
            Ids.newId(),
            PHARMACY_ID,
            jobId,
            "FSSAI",
            "P",
            Map.of(),
            null,
            "WARN",
            null,
            List.of(),
            0,
            null,
            NOW,
            NOW);
    assertThat(compute.invoke(null, List.of(pass, warn))).isEqualTo("PASS");
  }

  @Test
  void drugLicencePassWithNullDetailsSkipsExpiryEnforcement() throws Exception {
    UUID jobId = Ids.newId();
    UUID verificationId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PENDING", false, NOW, null));
    verificationStore.insert(
        new KycVerificationRecord(
            verificationId,
            PHARMACY_ID,
            jobId,
            "DRUG_LICENCE",
            StubDrugLicenceVerificationClient.API_PROVIDER,
            Map.of("licence_number", "DL-MH-1"),
            null,
            "PENDING",
            null,
            List.of(),
            0,
            null,
            null,
            NOW));
    String body =
        "{\"provider\":\"MH_DRUG_CONTROL_API\",\"job_id\":\""
            + jobId
            + "\",\"verification_type\":\"DRUG_LICENCE\",\"status\":\"PASS\",\"data\":null}";
    service.handleWebhookCallback(sign(body), body.getBytes());
    assertThat(verificationStore.findById(verificationId).orElseThrow().status()).isEqualTo("PASS");
  }

  @Test
  void triggerGstinOnlyCheck() {
    Map<String, Object> result =
        service.adminTriggerAutoVerify(adminPrincipal(), PHARMACY_ID, List.of("GSTIN"));
    assertThat(result).containsKey("gstin_result");
    assertThat(result).doesNotContainKey("fssai_result");
  }

  @Test
  void webhookBlankFieldValueReturns400() throws Exception {
    String body =
        "{\"provider\":\"FSSAI_PORTAL_API\",\"job_id\":\""
            + Ids.newId()
            + "\",\"verification_type\":\"   \",\"status\":\"PASS\"}";
    assertThatThrownBy(() -> service.handleWebhookCallback(sign(body), body.getBytes()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void computeOverallStatusPendingWithTerminalErrorIsPartial() throws Exception {
    Method compute = AutoKycService.class.getDeclaredMethod("computeOverallStatus", List.class);
    compute.setAccessible(true);
    UUID jobId = Ids.newId();
    KycVerificationRecord pending =
        new KycVerificationRecord(
            Ids.newId(),
            PHARMACY_ID,
            jobId,
            "FSSAI",
            "P",
            Map.of(),
            null,
            "PENDING",
            null,
            List.of(),
            0,
            null,
            null,
            NOW);
    KycVerificationRecord terminalError =
        new KycVerificationRecord(
            Ids.newId(),
            PHARMACY_ID,
            jobId,
            "GSTIN",
            "P",
            Map.of(),
            null,
            "ERROR",
            null,
            List.of(),
            3,
            null,
            NOW,
            NOW);
    assertThat(compute.invoke(null, List.of(pending, terminalError))).isEqualTo("PARTIAL");
  }

  @Test
  void drugLicenceFailStatusSkipsExpiryEnforcement() throws Exception {
    UUID jobId = Ids.newId();
    UUID verificationId = Ids.newId();
    jobStore.insert(
        new AutoKycJobRecord(jobId, PHARMACY_ID, ADMIN_ID, "ADMIN", "PENDING", false, NOW, null));
    verificationStore.insert(
        new KycVerificationRecord(
            verificationId,
            PHARMACY_ID,
            jobId,
            "DRUG_LICENCE",
            StubDrugLicenceVerificationClient.API_PROVIDER,
            Map.of("licence_number", "DL-MH-1"),
            null,
            "PENDING",
            null,
            List.of(),
            0,
            null,
            null,
            NOW));
    String body =
        "{\"provider\":\"MH_DRUG_CONTROL_API\",\"job_id\":\""
            + jobId
            + "\",\"verification_type\":\"DRUG_LICENCE\",\"status\":\"FAIL\",\"data\":{\"expiry_date\":\"2028-01-01\"}}";
    service.handleWebhookCallback(sign(body), body.getBytes());
    assertThat(verificationStore.findById(verificationId).orElseThrow().status()).isEqualTo("FAIL");
  }

  @Test
  void extractPincodeReturnsValueWhenPresent() throws Exception {
    Method extract = AutoKycService.class.getDeclaredMethod("extractPincode", Map.class);
    extract.setAccessible(true);
    assertThat(extract.invoke(null, Map.of("pincode", "560001"))).isEqualTo("560001");
    assertThat(extract.invoke(null, Map.of("line1", "x"))).isNull();
    assertThat(extract.invoke(null, (Object) null)).isNull();
  }

  private MedmatePrincipal adminPrincipal() {
    return new MedmatePrincipal(ADMIN_ID, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  private String sign(String body) {
    return "sha256="
        + AutoKycService.hmacSha256Hex(WEBHOOK_SECRET, body.getBytes(StandardCharsets.UTF_8));
  }
}

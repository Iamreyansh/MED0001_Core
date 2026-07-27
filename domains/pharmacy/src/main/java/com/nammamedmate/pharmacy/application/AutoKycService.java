package com.nammamedmate.pharmacy.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.adapter.out.kyc.StubDrugLicenceVerificationClient;
import com.nammamedmate.pharmacy.adapter.out.kyc.StubFssaiVerificationClient;
import com.nammamedmate.pharmacy.application.port.out.AutoKycJobStore;
import com.nammamedmate.pharmacy.application.port.out.AutoKycJobStore.AutoKycJobRecord;
import com.nammamedmate.pharmacy.application.port.out.KycGovernmentApiPort;
import com.nammamedmate.pharmacy.application.port.out.KycGovernmentApiPort.DrugLicenceVerificationPort;
import com.nammamedmate.pharmacy.application.port.out.KycGovernmentApiPort.FssaiVerificationPort;
import com.nammamedmate.pharmacy.application.port.out.KycGovernmentApiPort.GstinVerificationPort;
import com.nammamedmate.pharmacy.application.port.out.KycGovernmentApiPort.KycCheckResult;
import com.nammamedmate.pharmacy.application.port.out.KycVerificationStore;
import com.nammamedmate.pharmacy.application.port.out.KycVerificationStore.KycVerificationRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore.PharmacyRecord;
import com.nammamedmate.pharmacy.application.port.out.PincodeZoneStore;
import com.nammamedmate.pharmacy.domain.KycRequestSanitizer;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutoKycService {

  static final int TRIGGER_LIMIT = 10;
  static final int TRIGGER_WINDOW = 60;
  static final int RESULT_LIMIT = 60;
  static final int RESULT_WINDOW = 60;
  static final int ESTIMATED_COMPLETION_MINUTES = 10;
  static final int MAX_RETRIES = 3;
  static final Duration[] RETRY_BACKOFF = {
    Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(90)
  };
  static final Duration ASYNC_CHECK_TIMEOUT = Duration.ofMinutes(30);
  static final String LOCAL_WEBHOOK_SECRET = "local-kyc-webhook-secret";

  static final Set<String> ALL_CHECKS = Set.of("GSTIN", "DRUG_LICENCE", "FSSAI");
  static final Set<String> ASYNC_CHECKS = Set.of("DRUG_LICENCE", "FSSAI");
  static final Set<String> TERMINAL_STATUSES = Set.of("PASS", "FAIL", "ERROR", "WARN");
  static final Set<String> RESOLVED_JOB_STATUSES = Set.of("PASS", "FAIL", "ERROR");

  private final PharmacyRegistrationStore pharmacies;
  private final AutoKycJobStore jobs;
  private final KycVerificationStore verifications;
  private final PincodeZoneStore pincodeZones;
  private final GstinVerificationPort gstinPort;
  private final DrugLicenceVerificationPort drugLicencePort;
  private final FssaiVerificationPort fssaiPort;
  private final OutboxPublisher outbox;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final boolean autoVerificationEnabled;
  private final String webhookSecret;
  private AutoKycService self;

  @Autowired
  void setSelf(@Lazy AutoKycService self) {
    this.self = self;
  }

  public AutoKycService(
      PharmacyRegistrationStore pharmacies,
      AutoKycJobStore jobs,
      KycVerificationStore verifications,
      PincodeZoneStore pincodeZones,
      GstinVerificationPort gstinPort,
      DrugLicenceVerificationPort drugLicencePort,
      FssaiVerificationPort fssaiPort,
      OutboxPublisher outbox,
      RateLimiter rateLimiter,
      Clock clock,
      @Value("${medmate.kyc.auto-verification-enabled:false}") boolean autoVerificationEnabled,
      @Value("${medmate.kyc.webhook-secret:}") String webhookSecret) {
    this.pharmacies = pharmacies;
    this.jobs = jobs;
    this.verifications = verifications;
    this.pincodeZones = pincodeZones;
    this.gstinPort = gstinPort;
    this.drugLicencePort = drugLicencePort;
    this.fssaiPort = fssaiPort;
    this.outbox = outbox;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.autoVerificationEnabled = autoVerificationEnabled;
    this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
  }

  public static void validateWebhookSecretForDeployedProfile(
      String secret, boolean deployedProfile) {
    if (!deployedProfile) {
      return;
    }
    if (secret == null || secret.isBlank() || LOCAL_WEBHOOK_SECRET.equals(secret)) {
      throw new IllegalStateException(
          "medmate.kyc.webhook-secret must be injected via Secrets Manager for staging/prod");
    }
  }

  // ─── Admin trigger ───────────────────────────────────────────────────────────

  @Transactional
  public Map<String, Object> adminTriggerAutoVerify(
      MedmatePrincipal principal, UUID pharmacyId, List<String> checksRaw) {
    requireTriggerAdmin(principal);
    requireRateLimit(
        "admin:kyc:auto-verify:" + principal.subject(),
        TRIGGER_LIMIT,
        TRIGGER_WINDOW,
        "RATE_LIMIT_EXCEEDED");
    if (!autoVerificationEnabled) {
      throw new AppException(
          "FEATURE_FLAG_DISABLED", "Auto KYC verification is disabled on this platform", 503);
    }
    List<String> checks = resolveChecks(checksRaw);
    return triggerAutoVerify(pharmacyId, principal.subject(), "ADMIN", checks);
  }

  // ─── System trigger (from outbox after KYC submit) ───────────────────────────

  @Transactional
  public void handleAutoVerifyRequested(UUID pharmacyId) {
    if (!autoVerificationEnabled) {
      return;
    }
    PharmacyRecord pharmacy = pharmacies.findById(pharmacyId).orElse(null);
    if (pharmacy == null || !"KYC_SUBMITTED".equals(pharmacy.status())) {
      return;
    }
    if (jobs.findInProgressByPharmacy(pharmacyId).isPresent()) {
      return;
    }
    triggerAutoVerify(pharmacyId, null, "SYSTEM", List.copyOf(ALL_CHECKS));
  }

  /** GSTIN-only re-check for ACTIVE pharmacies after profile tax update (STORY-005). */
  @Transactional
  public void triggerGstinReverification(UUID pharmacyId) {
    if (!autoVerificationEnabled) {
      return;
    }
    PharmacyRecord pharmacy = pharmacies.findById(pharmacyId).orElse(null);
    if (pharmacy == null || pharmacy.gstin() == null || pharmacy.gstin().isBlank()) {
      return;
    }
    Instant now = clock.instant();
    UUID jobId = Ids.newId();
    jobs.insert(
        new AutoKycJobRecord(
            jobId, pharmacyId, null, "GSTIN_REVERIFY", "PENDING", false, now, null));
    runGstinCheck(pharmacy, jobId, now);
    reevaluateJob(jobId);
  }

  // ─── Admin get result ────────────────────────────────────────────────────────

  public Map<String, Object> adminGetAutoVerifyResult(
      MedmatePrincipal principal, UUID pharmacyId, UUID jobId) {
    requireResultAdmin(principal);
    requireRateLimit(
        "admin:kyc:auto-verify-result:" + principal.subject(),
        RESULT_LIMIT,
        RESULT_WINDOW,
        "RATE_LIMIT_EXCEEDED");

    pharmacies
        .findById(pharmacyId)
        .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));

    AutoKycJobRecord job =
        (jobId == null
                ? jobs.findLatestByPharmacy(pharmacyId)
                : jobs.findById(jobId).filter(j -> j.pharmacyId().equals(pharmacyId)))
            .orElseThrow(
                () ->
                    new AppException(
                        "NO_AUTO_KYC_JOB", "No auto-KYC job has been run for this pharmacy", 404));

    return buildJobResult(pharmacyId, job);
  }

  /** Summary for admin GET /kyc (latest job only). */
  public Map<String, Object> latestAutoKycSummary(UUID pharmacyId) {
    return jobs.findLatestByPharmacy(pharmacyId)
        .map(job -> buildJobResult(pharmacyId, job))
        .orElse(null);
  }

  // ─── Webhook callback ────────────────────────────────────────────────────────

  public Map<String, Object> handleWebhookCallback(String signatureHeader, byte[] rawBody) {
    verifyWebhookSignature(signatureHeader, rawBody);
    Map<String, Object> body = parseJsonBody(rawBody);

    String provider = stringField(body, "provider");
    UUID jobId = uuidField(body, "job_id");
    String verificationType = stringField(body, "verification_type");
    String status = stringField(body, "status");

    if (!"MH_DRUG_CONTROL_API".equals(provider) && !"FSSAI_PORTAL_API".equals(provider)) {
      throw new AppException("VALIDATION_ERROR", "Unknown provider: " + provider, 400);
    }
    if (!"DRUG_LICENCE".equals(verificationType) && !"FSSAI".equals(verificationType)) {
      throw new AppException("VALIDATION_ERROR", "Invalid verification_type", 400);
    }
    if (!"PASS".equals(status)
        && !"FAIL".equals(status)
        && !"ERROR".equals(status)
        && !"WARN".equals(status)) {
      throw new AppException("VALIDATION_ERROR", "status must be PASS, FAIL, ERROR, or WARN", 400);
    }

    AutoKycJobRecord job =
        jobs.findById(jobId)
            .orElseThrow(() -> new AppException("JOB_NOT_FOUND", "Auto-KYC job not found", 404));

    KycVerificationRecord verification =
        verifications
            .findByJobAndType(jobId, verificationType)
            .orElseThrow(
                () -> new AppException("JOB_NOT_FOUND", "Verification record not found", 404));

    if (RESOLVED_JOB_STATUSES.contains(job.overallStatus())
        || isVerificationTerminal(verification)) {
      return idempotentWebhookAck(verification.id(), true);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) body.get("data");
    KycCheckResult result = webhookToResult(provider, verificationType, status, data);
    self.persistCheckOutcome(verification, result);
    return idempotentWebhookAck(verification.id(), false);
  }

  // ─── Async processing (outbox consumer) ──────────────────────────────────────

  public void processAsyncCheck(UUID verificationId) {
    KycVerificationRecord verification =
        verifications
            .findById(verificationId)
            .orElseThrow(
                () -> new AppException("VERIFICATION_NOT_FOUND", "Verification not found", 404));
    if (!"PENDING".equals(verification.status()) && !"ERROR".equals(verification.status())) {
      return;
    }
    PharmacyRecord pharmacy =
        pharmacies
            .findById(verification.pharmacyId())
            .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));

    KycCheckResult result = invokeExternalCheck(verification, pharmacy);
    self.persistCheckOutcome(verification, result);
  }

  public int processStaleAsyncChecks() {
    Instant cutoff = clock.instant().minus(ASYNC_CHECK_TIMEOUT);
    List<KycVerificationRecord> stale = verifications.findStalePending(cutoff, 25);
    for (KycVerificationRecord record : stale) {
      self.markStaleCheckAsError(record);
    }
    return stale.size();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void persistCheckOutcome(KycVerificationRecord verification, KycCheckResult result) {
    applyCheckResult(verification, result);
    reevaluateJob(verification.jobId());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markStaleCheckAsError(KycVerificationRecord verification) {
    KycCheckResult timeout =
        new KycCheckResult(
            "ERROR",
            verification.apiProvider(),
            Map.of("reason", "ASYNC_CHECK_TIMEOUT"),
            null,
            Map.of("reason", "ASYNC_CHECK_TIMEOUT"),
            List.of(),
            false);
    applyCheckResult(verification, timeout);
    reevaluateJob(verification.jobId());
  }

  private KycCheckResult invokeExternalCheck(
      KycVerificationRecord verification, PharmacyRecord pharmacy) {
    return switch (verification.verificationType()) {
      case "GSTIN" -> {
        String businessName =
            pharmacy.businessName() == null ? pharmacy.name() : pharmacy.businessName();
        yield gstinPort.verifyGstin(pharmacy.gstin(), businessName);
      }
      case "DRUG_LICENCE" ->
          drugLicencePort.verifyDrugLicence(
              pharmacy.drugLicenceNumber(), pharmacy.licenceStateCode());
      case "FSSAI" -> fssaiPort.verifyFssai(pharmacy.fssaiNumber());
      default -> throw new AppException("INVALID_CHECK_TYPE", "Unknown verification type", 400);
    };
  }

  // ─── Internal orchestration ──────────────────────────────────────────────────

  private Map<String, Object> triggerAutoVerify(
      UUID pharmacyId, UUID triggeredBy, String triggerSource, List<String> checks) {
    PharmacyRecord pharmacy =
        pharmacies
            .findById(pharmacyId)
            .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));

    if (!"KYC_SUBMITTED".equals(pharmacy.status())) {
      throw new AppException(
          "KYC_NOT_SUBMITTED", "Pharmacy has not submitted KYC documents yet", 409);
    }
    if (jobs.findInProgressByPharmacy(pharmacyId).isPresent()) {
      throw new AppException(
          "AUTO_KYC_IN_PROGRESS", "A previous auto-KYC job is still running", 409);
    }

    Instant now = clock.instant();
    UUID jobId = Ids.newId();
    jobs.insert(
        new AutoKycJobRecord(
            jobId, pharmacyId, triggeredBy, triggerSource, "PENDING", false, now, null));

    Map<String, Object> gstinResult = null;
    for (String check : checks) {
      if ("GSTIN".equals(check)) {
        gstinResult = runGstinCheck(pharmacy, jobId, now);
      } else if (ASYNC_CHECKS.contains(check)) {
        dispatchAsyncCheck(pharmacy, jobId, check, now);
      }
    }

    reevaluateJob(jobId);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("job_id", jobId.toString());
    data.put("checks_triggered", checks);
    if (checks.contains("GSTIN")) {
      data.put("gstin_result", gstinResult);
    }
    if (checks.contains("DRUG_LICENCE")) {
      data.put("drug_licence_result", pendingAsyncMessage());
    }
    if (checks.contains("FSSAI")) {
      data.put("fssai_result", pendingAsyncMessage());
    }
    data.put("estimated_completion_minutes", ESTIMATED_COMPLETION_MINUTES);
    return data;
  }

  private Map<String, Object> runGstinCheck(PharmacyRecord pharmacy, UUID jobId, Instant now) {
    String businessName =
        pharmacy.businessName() == null ? pharmacy.name() : pharmacy.businessName();
    KycCheckResult result = gstinPort.verifyGstin(pharmacy.gstin(), businessName);
    UUID verificationId = Ids.newId();
    KycVerificationRecord record =
        newVerificationRecord(verificationId, pharmacy.id(), jobId, "GSTIN", result, now);
    verifications.insert(record);
    applyCheckResult(record, result);
    return checkResultMap(verifications.findById(verificationId).orElse(record));
  }

  private void dispatchAsyncCheck(
      PharmacyRecord pharmacy, UUID jobId, String verificationType, Instant now) {
    String provider =
        "DRUG_LICENCE".equals(verificationType)
            ? StubDrugLicenceVerificationClient.API_PROVIDER
            : StubFssaiVerificationClient.API_PROVIDER;
    Map<String, Object> request =
        "DRUG_LICENCE".equals(verificationType)
            ? KycRequestSanitizer.sanitise(
                Map.of(
                    "licence_number",
                    pharmacy.drugLicenceNumber(),
                    "state",
                    pharmacy.licenceStateCode()))
            : KycRequestSanitizer.sanitise(Map.of("licence_number", pharmacy.fssaiNumber()));

    UUID verificationId = Ids.newId();
    verifications.insert(
        new KycVerificationRecord(
            verificationId,
            pharmacy.id(),
            jobId,
            verificationType,
            provider,
            request,
            null,
            "PENDING",
            null,
            List.of(),
            0,
            null,
            null,
            now));

    outbox.publish(
        DomainEvent.of(
            "pharmacy.kyc.async_check_requested",
            "pharmacy",
            pharmacy.id(),
            Map.of(
                "pharmacy_id", pharmacy.id().toString(),
                "job_id", jobId.toString(),
                "verification_id", verificationId.toString(),
                "verification_type", verificationType)));
    processAsyncCheck(verificationId);
  }

  private void applyCheckResult(KycVerificationRecord verification, KycCheckResult result) {
    String status = normalizeStatus(verification.verificationType(), result);
    Map<String, Object> details = result.details();
    List<Map<String, Object>> flags = new ArrayList<>(result.adminFlags());
    int retryCount = verification.retryCount();
    Instant nextRetryAt = null;
    Instant verifiedAt = null;

    if ("DRUG_LICENCE".equals(verification.verificationType())
        && "PASS".equals(status)
        && details != null
        && details.get("expiry_date") instanceof String expiryRaw) {
      LocalDate expiry = LocalDate.parse(expiryRaw);
      if (KycGovernmentApiPort.isLicenceExpiringSoon(expiry, LocalDate.now(clock))) {
        status = "FAIL";
        details = new LinkedHashMap<>(details);
        details.put("reason", "LICENCE_EXPIRING_SOON");
      }
    }

    if ("ERROR".equals(status) && result.transientError()) {
      if (retryCount < MAX_RETRIES) {
        nextRetryAt = clock.instant().plus(RETRY_BACKOFF[retryCount]);
        retryCount++;
      } else {
        verifiedAt = clock.instant();
        routeToManualQueue(verification.pharmacyId(), verification.jobId());
      }
    } else if (TERMINAL_STATUSES.contains(status)) {
      verifiedAt = clock.instant();
    }

    verifications.updateResult(
        verification.id(),
        status,
        result.responsePayload(),
        details,
        flags,
        retryCount,
        nextRetryAt,
        verifiedAt);
  }

  private void reevaluateJob(UUID jobId) {
    List<KycVerificationRecord> checks = verifications.findByJobId(jobId);
    if (checks.isEmpty()) {
      return;
    }

    String overall = computeOverallStatus(checks);
    Instant now = clock.instant();
    AutoKycJobRecord job = jobs.findById(jobId).orElseThrow();
    Instant completedAt = RESOLVED_JOB_STATUSES.contains(overall) ? now : null;
    jobs.updateOverallStatus(jobId, overall, completedAt);

    if ("PASS".equals(overall)) {
      autoActivate(job.pharmacyId(), jobId);
    } else if ("FAIL".equals(overall) || "ERROR".equals(overall)) {
      routeToManualQueue(job.pharmacyId(), jobId);
    }
  }

  private void autoActivate(UUID pharmacyId, UUID jobId) {
    PharmacyRecord pharmacy =
        pharmacies
            .findById(pharmacyId)
            .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));

    String pincode = extractPincode(pharmacy.address());
    UUID zoneId =
        pincodeZones
            .findZoneIdByPincode(pincode)
            .orElse(UUID.fromString("a0000001-0000-4000-8000-000000000001"));

    Instant now = clock.instant();
    pharmacies.activateAfterAutoKyc(pharmacyId, zoneId, now);
    jobs.markAutoActivated(jobId, now);

    outbox.publish(
        DomainEvent.of(
            "pharmacy.kyc.auto_activated",
            "pharmacy",
            pharmacyId,
            Map.of(
                "pharmacy_id", pharmacyId.toString(),
                "job_id", jobId.toString(),
                "zone_id", zoneId.toString())));

    outbox.publish(
        DomainEvent.of(
            "pharmacy.notification.welcome",
            "pharmacy",
            pharmacyId,
            Map.of(
                "pharmacy_id", pharmacyId.toString(),
                "channels", List.of("WHATSAPP", "EMAIL"))));
  }

  private void routeToManualQueue(UUID pharmacyId, UUID jobId) {
    outbox.publish(
        DomainEvent.of(
            "pharmacy.kyc.manual_review_required",
            "pharmacy",
            pharmacyId,
            Map.of("pharmacy_id", pharmacyId.toString(), "job_id", jobId.toString())));
  }

  private Map<String, Object> buildJobResult(UUID pharmacyId, AutoKycJobRecord job) {
    List<KycVerificationRecord> checks = verifications.findByJobId(job.id());
    List<Map<String, Object>> checkMaps = new ArrayList<>();
    List<Map<String, Object>> allFlags = new ArrayList<>();

    for (KycVerificationRecord check : checks) {
      checkMaps.add(checkResultMap(check));
      allFlags.addAll(check.adminFlags());
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("job_id", job.id().toString());
    data.put("overall_status", job.overallStatus());
    data.put("auto_activated", job.autoActivated());
    data.put("triggered_at", job.triggeredAt().toString());
    data.put("completed_at", job.completedAt() != null ? job.completedAt().toString() : null);
    data.put("checks", checkMaps);
    data.put("admin_flags", allFlags);
    return data;
  }

  private static Map<String, Object> idempotentWebhookAck(UUID verificationId, boolean duplicate) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("acknowledged", true);
    response.put("verification_id", verificationId.toString());
    if (duplicate) {
      response.put("duplicate", true);
    }
    return response;
  }

  private static boolean isVerificationTerminal(KycVerificationRecord verification) {
    return TERMINAL_STATUSES.contains(verification.status()) && verification.verifiedAt() != null;
  }

  private static Map<String, Object> checkResultMap(KycVerificationRecord check) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("verification_id", check.id().toString());
    m.put("verification_type", check.verificationType());
    m.put("api_provider", check.apiProvider());
    m.put("status", check.status());
    m.put("details", check.details());
    m.put("checked_at", check.verifiedAt() != null ? check.verifiedAt().toString() : null);
    m.put("retry_count", check.retryCount());
    return m;
  }

  private static Map<String, Object> pendingAsyncMessage() {
    return Map.of(
        "status",
        "PENDING",
        "message",
        "Async check dispatched. Poll auto-verify-result for status.");
  }

  private static String computeOverallStatus(List<KycVerificationRecord> checks) {
    boolean anyPending = false;
    boolean anyFail = false;
    boolean anyError = false;
    boolean allPass = true;

    for (KycVerificationRecord check : checks) {
      String status = check.status();
      if ("PENDING".equals(status) || ("ERROR".equals(status) && check.nextRetryAt() != null)) {
        anyPending = true;
        allPass = false;
      } else if ("FAIL".equals(status)) {
        anyFail = true;
        allPass = false;
      } else if ("ERROR".equals(status)) {
        anyError = true;
        allPass = false;
      } else if (!"PASS".equals(status) && !"WARN".equals(status)) {
        allPass = false;
      }
    }

    if (anyPending) {
      return anyFail || anyError ? "PARTIAL" : "PENDING";
    }
    if (anyFail) {
      return "FAIL";
    }
    if (anyError) {
      return "ERROR";
    }
    if (allPass) {
      return "PASS";
    }
    return "PARTIAL";
  }

  private static String normalizeStatus(String verificationType, KycCheckResult result) {
    String status = result.status();
    if ("WARN".equals(status)) {
      return "PASS";
    }
    return status == null ? "ERROR" : status;
  }

  private static KycVerificationRecord newVerificationRecord(
      UUID id, UUID pharmacyId, UUID jobId, String type, KycCheckResult result, Instant now) {
    return new KycVerificationRecord(
        id,
        pharmacyId,
        jobId,
        type,
        result.apiProvider(),
        result.requestPayload(),
        result.responsePayload(),
        result.status(),
        result.details(),
        result.adminFlags(),
        0,
        null,
        null,
        now);
  }

  private static List<String> resolveChecks(List<String> checksRaw) {
    if (checksRaw == null || checksRaw.isEmpty()) {
      return List.copyOf(ALL_CHECKS);
    }
    LinkedHashSet<String> resolved = new LinkedHashSet<>();
    for (String raw : checksRaw) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String type = raw.trim().toUpperCase(Locale.ROOT);
      if (!ALL_CHECKS.contains(type)) {
        throw new AppException(
            "INVALID_CHECK_TYPE",
            "Unknown check type: " + raw + "; allowed: GSTIN, DRUG_LICENCE, FSSAI",
            400);
      }
      resolved.add(type);
    }
    if (resolved.isEmpty()) {
      return List.copyOf(ALL_CHECKS);
    }
    return List.copyOf(resolved);
  }

  private KycCheckResult webhookToResult(
      String provider, String verificationType, String status, Map<String, Object> data) {
    Map<String, Object> details = data == null ? Map.of() : new LinkedHashMap<>(data);
    Map<String, Object> request =
        KycRequestSanitizer.sanitise(
            Map.of("provider", provider, "verification_type", verificationType));
    boolean transientError = "ERROR".equals(status);
    return new KycCheckResult(
        status, provider, request, details, details, List.of(), transientError);
  }

  private void verifyWebhookSignature(String signatureHeader, byte[] rawBody) {
    if (signatureHeader == null || signatureHeader.isBlank()) {
      throw new AppException("INVALID_WEBHOOK_SIGNATURE", "Missing webhook signature", 401);
    }
    String expected = hmacSha256Hex(webhookSecret, rawBody);
    String provided = signatureHeader.trim();
    if (provided.startsWith("sha256=")) {
      provided = provided.substring("sha256=".length());
    }
    if (!constantTimeEquals(expected.toLowerCase(Locale.ROOT), provided.toLowerCase(Locale.ROOT))) {
      throw new AppException("INVALID_WEBHOOK_SIGNATURE", "HMAC signature does not match", 401);
    }
  }

  static String hmacSha256Hex(String secret, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(body);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a.length() != b.length()) {
      return false;
    }
    int result = 0;
    for (int i = 0; i < a.length(); i++) {
      result |= a.charAt(i) ^ b.charAt(i);
    }
    return result == 0;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseJsonBody(byte[] rawBody) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readValue(rawBody, Map.class);
    } catch (Exception ex) {
      throw new AppException("VALIDATION_ERROR", "Invalid JSON body", 400);
    }
  }

  private static String stringField(Map<String, Object> body, String key) {
    Object value = body.get(key);
    if (value == null || String.valueOf(value).isBlank()) {
      throw new AppException("VALIDATION_ERROR", key + " is required", 400);
    }
    return String.valueOf(value).trim();
  }

  private static UUID uuidField(Map<String, Object> body, String key) {
    try {
      return UUID.fromString(stringField(body, key));
    } catch (IllegalArgumentException ex) {
      throw new AppException("VALIDATION_ERROR", key + " must be a UUID", 400);
    }
  }

  @SuppressWarnings("unchecked")
  private static String extractPincode(Map<String, Object> address) {
    if (address == null) {
      return null;
    }
    Object pincode = address.get("pincode");
    return pincode == null ? null : String.valueOf(pincode);
  }

  private static void requireTriggerAdmin(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Admin role required to trigger auto-KYC", 403);
    }
  }

  private static void requireResultAdmin(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_COMPLIANCE) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  private void requireRateLimit(String key, int limit, int window, String code) {
    if (!rateLimiter.tryAcquire(key, limit, window)) {
      throw new AppException(code, "Too many requests; try again later", 429);
    }
  }
}

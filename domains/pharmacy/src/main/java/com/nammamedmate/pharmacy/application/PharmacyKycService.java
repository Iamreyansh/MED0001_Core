package com.nammamedmate.pharmacy.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.messaging.DomainEvent;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PharmacyKycService {

  static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
  static final Duration SIGNED_URL_TTL = Duration.ofHours(1);
  static final int ESTIMATED_REVIEW_HOURS = 24;

  static final int UPLOAD_LIMIT = 20;
  static final int UPLOAD_WINDOW = 60;
  static final int LIST_LIMIT = 60;
  static final int LIST_WINDOW = 60;
  static final int DELETE_LIMIT = 20;
  static final int DELETE_WINDOW = 60;
  static final int SUBMIT_LIMIT = 5;
  static final int SUBMIT_WINDOW = 60;
  static final int ADMIN_GET_LIMIT = 120;
  static final int ADMIN_GET_WINDOW = 60;
  static final int ADMIN_VERIFY_LIMIT = 60;
  static final int ADMIN_VERIFY_WINDOW = 60;

  static final Set<String> ALLOWED_MIME_TYPES =
      Set.of("application/pdf", "image/jpeg", "image/png");

  static final Set<String> VALID_DOCUMENT_TYPES =
      Set.of(
          "GSTIN_CERTIFICATE",
          "DRUG_LICENCE",
          "FSSAI_CERTIFICATE",
          "PAN_CARD",
          "BANK_STATEMENT",
          "PROPRIETOR_ID");

  /** Always required document types (excluding FSSAI which is conditional). */
  static final Set<String> ALWAYS_REQUIRED =
      Set.of("GSTIN_CERTIFICATE", "DRUG_LICENCE", "PAN_CARD");

  /** One of these two must be present. */
  static final Set<String> IDENTITY_DOCS = Set.of("BANK_STATEMENT", "PROPRIETOR_ID");

  /** Expiry date is mandatory for these types. */
  static final Set<String> REQUIRES_EXPIRY = Set.of("DRUG_LICENCE", "FSSAI_CERTIFICATE");

  /** Business types where FSSAI is mandatory. */
  static final Set<String> FSSAI_MANDATORY_TYPES = Set.of("PHARMACY", "HOSPITAL");

  /** Document statuses that block a new upload for the same type. */
  static final Set<String> BLOCKING_STATUSES = Set.of("UPLOADED", "UNDER_REVIEW", "VERIFIED");

  private final PharmacyRegistrationStore pharmacies;
  private final KycDocumentStore kycDocs;
  private final KycObjectStore kycObjectStore;
  private final VirusScanner virusScanner;
  private final PresignedUrlService presignedUrls;
  private final OutboxPublisher outbox;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final boolean autoVerificationEnabled;
  private final AutoKycService autoKyc;

  public PharmacyKycService(
      PharmacyRegistrationStore pharmacies,
      KycDocumentStore kycDocs,
      KycObjectStore kycObjectStore,
      VirusScanner virusScanner,
      PresignedUrlService presignedUrls,
      OutboxPublisher outbox,
      RateLimiter rateLimiter,
      Clock clock,
      @Value("${medmate.kyc.auto-verification-enabled:false}") boolean autoVerificationEnabled,
      AutoKycService autoKyc) {
    this.pharmacies = pharmacies;
    this.kycDocs = kycDocs;
    this.kycObjectStore = kycObjectStore;
    this.virusScanner = virusScanner;
    this.presignedUrls = presignedUrls;
    this.outbox = outbox;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.autoVerificationEnabled = autoVerificationEnabled;
    this.autoKyc = autoKyc;
  }

  // ─── Upload ──────────────────────────────────────────────────────────────────

  @Transactional
  public Map<String, Object> uploadDocument(
      MedmatePrincipal principal,
      String documentTypeRaw,
      byte[] fileBytes,
      String originalFileName,
      String contentType,
      String expiryDateRaw) {
    requireOwner(principal);
    UUID pharmacyId = principal.pharmacyId();
    requireRateLimit(
        "pharmacy:kyc:upload:" + pharmacyId, UPLOAD_LIMIT, UPLOAD_WINDOW, "RATE_LIMIT_EXCEEDED");

    PharmacyRecord pharmacy = requirePharmacy(pharmacyId);

    if ("ACTIVE".equals(pharmacy.status())) {
      throw new AppException(
          "PHARMACY_ALREADY_ACTIVE",
          "Pharmacy is already active; contact admin to upload new documents",
          403);
    }

    String documentType = validateDocumentType(documentTypeRaw);

    // File size first (cheap) then claimed MIME + magic-byte sniff
    if (fileBytes == null || fileBytes.length == 0) {
      throw new AppException("INVALID_FILE_TYPE", "File is empty", 400);
    }
    if (fileBytes.length > MAX_FILE_BYTES) {
      throw new AppException("FILE_TOO_LARGE", "File exceeds 10 MB limit", 400);
    }

    String mimeType = resolveMimeType(fileBytes, contentType);

    // Expiry date validation
    LocalDate expiryDate = null;
    if (REQUIRES_EXPIRY.contains(documentType)) {
      if (expiryDateRaw == null || expiryDateRaw.isBlank()) {
        throw new AppException(
            "EXPIRY_DATE_REQUIRED", documentType + " requires an expiry_date (YYYY-MM-DD)", 400);
      }
      try {
        expiryDate = LocalDate.parse(expiryDateRaw.trim());
      } catch (DateTimeParseException e) {
        throw new AppException(
            "EXPIRY_DATE_REQUIRED", "expiry_date must be in YYYY-MM-DD format", 400);
      }
      if (!expiryDate.isAfter(LocalDate.now(clock))) {
        throw new AppException("EXPIRY_DATE_IN_PAST", "expiry_date must be in the future", 400);
      }
    }

    // Virus scan
    try {
      virusScanner.scan(fileBytes, originalFileName);
    } catch (VirusScanner.VirusScanException e) {
      throw new AppException("FILE_SCAN_FAILED", "File failed virus scan: " + e.getMessage(), 400);
    }

    // Check existing non-REJECTED document for this type
    List<KycDocumentRecord> existing = kycDocs.findActiveByPharmacy(pharmacyId);
    for (KycDocumentRecord doc : existing) {
      if (doc.documentType().equals(documentType) && BLOCKING_STATUSES.contains(doc.status())) {
        throw new AppException(
            "DOCUMENT_TYPE_ALREADY_PENDING",
            "An active document of type " + documentType + " already exists",
            409);
      }
    }

    // Build storage key: kyc/{pharmacyId}/{documentType}/{uuid}.ext
    String ext = extensionFor(mimeType);
    UUID docId = Ids.newId();
    String fileKey =
        StorageObjectKeys.key(
            StorageObjectKeys.KYC, pharmacyId + "/" + documentType + "/" + docId + "." + ext);

    // Store file
    kycObjectStore.put(fileKey, fileBytes, mimeType);

    Instant now = clock.instant();
    KycDocumentRecord doc =
        new KycDocumentRecord(
            docId,
            pharmacyId,
            documentType,
            fileKey,
            originalFileName == null ? "document." + ext : originalFileName,
            fileBytes.length,
            mimeType,
            "UPLOADED",
            null,
            expiryDate,
            null,
            null,
            now,
            now);
    kycDocs.insert(doc);

    // Schedule expiry alerts for DRUG_LICENCE and FSSAI_CERTIFICATE
    if (expiryDate != null) {
      scheduleExpiryAlerts(docId, pharmacyId, expiryDate, documentType, now);
    }

    // Generate signed GET URL
    PresignedUrlService.PresignedUrl signedUrl =
        presignedUrls.createGetUrl(fileKey, SIGNED_URL_TTL);
    Instant urlExpiresAt = now.plus(SIGNED_URL_TTL);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("document_id", docId.toString());
    data.put("document_type", documentType);
    data.put("status", "UPLOADED");
    data.put("file_name", doc.fileName());
    data.put("file_size_bytes", (long) fileBytes.length);
    data.put("expiry_date", expiryDate != null ? expiryDate.toString() : null);
    data.put("uploaded_at", now.toString());
    data.put("signed_url", signedUrl.url());
    data.put("signed_url_expires_at", urlExpiresAt.toString());
    return data;
  }

  // ─── List ────────────────────────────────────────────────────────────────────

  public Map<String, Object> listDocuments(MedmatePrincipal principal) {
    requirePharmacyRole(principal);
    UUID pharmacyId = principal.pharmacyId();
    requireRateLimit(
        "pharmacy:kyc:list:" + pharmacyId, LIST_LIMIT, LIST_WINDOW, "RATE_LIMIT_EXCEEDED");

    PharmacyRecord pharmacy = requirePharmacy(pharmacyId);
    List<KycDocumentRecord> docs = kycDocs.findActiveByPharmacy(pharmacyId);
    Instant now = clock.instant();

    List<String> required = requiredDocumentTypes(pharmacy.businessType());
    List<String> missing = computeMissing(docs, required);
    boolean readyToSubmit = missing.isEmpty();

    List<Map<String, Object>> docMaps = new ArrayList<>();
    for (KycDocumentRecord doc : docs) {
      PresignedUrlService.PresignedUrl signedUrl =
          presignedUrls.createGetUrl(doc.fileKey(), SIGNED_URL_TTL);
      docMaps.add(docToMap(doc, signedUrl.url(), now.plus(SIGNED_URL_TTL)));
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("kyc_status", pharmacy.status());
    data.put(
        "submitted_at",
        pharmacy.kycSubmittedAt() != null ? pharmacy.kycSubmittedAt().toString() : null);
    data.put("documents", docMaps);
    data.put("required_documents", required);
    data.put("missing_documents", missing);
    data.put("ready_to_submit", readyToSubmit);
    return data;
  }

  // ─── Delete ──────────────────────────────────────────────────────────────────

  @Transactional
  public Map<String, Object> deleteDocument(MedmatePrincipal principal, UUID documentId) {
    requireOwner(principal);
    UUID pharmacyId = principal.pharmacyId();
    requireRateLimit(
        "pharmacy:kyc:delete:" + pharmacyId, DELETE_LIMIT, DELETE_WINDOW, "RATE_LIMIT_EXCEEDED");

    KycDocumentRecord doc =
        kycDocs
            .findById(documentId, pharmacyId)
            .orElseThrow(() -> new AppException("DOCUMENT_NOT_FOUND", "Document not found", 404));

    switch (doc.status()) {
      case "VERIFIED" ->
          throw new AppException(
              "CANNOT_DELETE_VERIFIED",
              "Cannot delete a verified document; contact admin to unlock",
              403);
      case "UNDER_REVIEW" ->
          throw new AppException(
              "CANNOT_DELETE_UNDER_REVIEW",
              "Cannot delete a document under review; ask admin to reject first",
              403);
      default -> {
        // UPLOADED or REJECTED — allowed
      }
    }

    kycDocs.softDelete(documentId, clock.instant());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("document_id", documentId.toString());
    data.put("deleted", true);
    data.put("message", "Document removed. You can now upload a replacement.");
    return data;
  }

  // ─── Submit ──────────────────────────────────────────────────────────────────

  @Transactional
  public Map<String, Object> submitKyc(MedmatePrincipal principal) {
    requireOwner(principal);
    UUID pharmacyId = principal.pharmacyId();
    requireRateLimit(
        "pharmacy:kyc:submit:" + pharmacyId, SUBMIT_LIMIT, SUBMIT_WINDOW, "RATE_LIMIT_EXCEEDED");

    PharmacyRecord pharmacy = requirePharmacy(pharmacyId);

    if (!pharmacy.emailVerified()) {
      throw new AppException(
          "EMAIL_NOT_VERIFIED", "Please verify your email before submitting KYC", 400);
    }
    if ("ACTIVE".equals(pharmacy.status())) {
      throw new AppException("ALREADY_ACTIVE", "Pharmacy is already active", 409);
    }
    if ("KYC_SUBMITTED".equals(pharmacy.status())) {
      throw new AppException(
          "ALREADY_SUBMITTED", "KYC documents already submitted and under review", 409);
    }
    if (("REJECTED".equals(pharmacy.status()) || "KYC_REJECTED".equals(pharmacy.status()))
        && !pharmacy.canReapply()) {
      throw new AppException("CANNOT_REAPPLY", "This pharmacy is not eligible to reapply", 409);
    }

    List<KycDocumentRecord> docs = kycDocs.findActiveByPharmacy(pharmacyId);
    List<String> required = requiredDocumentTypes(pharmacy.businessType());
    List<String> missing = computeMissing(docs, required);
    if (!missing.isEmpty()) {
      throw new AppException(
          "DOCUMENTS_INCOMPLETE",
          "Missing or rejected required documents: " + missing,
          400,
          null,
          Map.of("missing_types", missing));
    }

    Instant now = clock.instant();
    // Transition UPLOADED → UNDER_REVIEW
    kycDocs.setAllUploadedToUnderReview(pharmacyId, now);
    pharmacies.updateStatus(pharmacyId, "KYC_SUBMITTED", now, now);

    outbox.publish(
        DomainEvent.of(
            "pharmacy.kyc.submitted",
            "pharmacy",
            pharmacyId,
            Map.of("pharmacy_id", pharmacyId.toString())));

    if (autoVerificationEnabled) {
      outbox.publish(
          DomainEvent.of(
              "pharmacy.kyc.auto_verify_requested",
              "pharmacy",
              pharmacyId,
              Map.of("pharmacy_id", pharmacyId.toString())));
      autoKyc.handleAutoVerifyRequested(pharmacyId);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("status", "KYC_SUBMITTED");
    data.put("submitted_at", now.toString());
    data.put("auto_kyc_triggered", autoVerificationEnabled);
    data.put("estimated_review_hours", ESTIMATED_REVIEW_HOURS);
    data.put(
        "message",
        "Your KYC documents have been submitted for review. You will be notified via email and WhatsApp.");
    return data;
  }

  // ─── Admin: Get KYC ──────────────────────────────────────────────────────────

  @Transactional
  public Map<String, Object> adminGetKyc(MedmatePrincipal principal, UUID pharmacyId) {
    requireAdminRole(principal);
    requireRateLimit(
        "admin:kyc:get:" + principal.subject(),
        ADMIN_GET_LIMIT,
        ADMIN_GET_WINDOW,
        "RATE_LIMIT_EXCEEDED");

    PharmacyRecord pharmacy =
        pharmacies
            .findById(pharmacyId)
            .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));

    List<KycDocumentRecord> docs = kycDocs.findActiveByPharmacy(pharmacyId);
    Instant now = clock.instant();
    UUID adminId = principal.subject();

    List<Map<String, Object>> docMaps = new ArrayList<>();
    for (KycDocumentRecord doc : docs) {
      PresignedUrlService.PresignedUrl signedUrl =
          presignedUrls.createGetUrl(doc.fileKey(), SIGNED_URL_TTL);
      // Log admin access (ponytail: logged at URL issuance; S3 GET can't callback to log)
      kycDocs.insertAccessAudit(
          new KycAccessAuditRecord(Ids.newId(), doc.id(), pharmacyId, adminId, now));
      Map<String, Object> docMap = docToMap(doc, signedUrl.url(), now.plus(SIGNED_URL_TTL));
      docMap.put("verified_by", doc.verifiedBy() != null ? doc.verifiedBy().toString() : null);
      docMap.put("verified_at", doc.verifiedAt() != null ? doc.verifiedAt().toString() : null);
      docMaps.add(docMap);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put(
        "business_name",
        pharmacy.businessName() == null ? pharmacy.name() : pharmacy.businessName());
    data.put("kyc_status", pharmacy.status());
    data.put(
        "submitted_at",
        pharmacy.kycSubmittedAt() != null ? pharmacy.kycSubmittedAt().toString() : null);
    data.put("auto_kyc_result", autoKyc.latestAutoKycSummary(pharmacyId));
    data.put("documents", docMaps);
    return data;
  }

  // ─── Admin: Verify/Reject ────────────────────────────────────────────────────

  @Transactional
  public Map<String, Object> adminVerifyDocument(
      MedmatePrincipal principal,
      UUID pharmacyId,
      UUID docId,
      boolean verified,
      String rejectionReason) {
    requireAdminRole(principal);
    requireRateLimit(
        "admin:kyc:verify:" + principal.subject(),
        ADMIN_VERIFY_LIMIT,
        ADMIN_VERIFY_WINDOW,
        "RATE_LIMIT_EXCEEDED");

    // Verify pharmacy exists
    pharmacies
        .findById(pharmacyId)
        .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));

    KycDocumentRecord doc =
        kycDocs
            .findById(docId, pharmacyId)
            .orElseThrow(
                () ->
                    new AppException(
                        "DOCUMENT_NOT_FOUND", "Document not found under this pharmacy", 404));

    if ("VERIFIED".equals(doc.status())) {
      throw new AppException("DOCUMENT_ALREADY_VERIFIED", "Document is already verified", 409);
    }

    String newStatus;
    String reason = null;
    if (verified) {
      newStatus = "VERIFIED";
    } else {
      if (rejectionReason == null || rejectionReason.isBlank()) {
        throw new AppException(
            "REJECTION_REASON_REQUIRED", "rejection_reason is required when verified=false", 400);
      }
      if (rejectionReason.length() > 500) {
        throw new AppException(
            "REJECTION_REASON_REQUIRED", "rejection_reason must be 500 characters or fewer", 400);
      }
      newStatus = "REJECTED";
      reason = rejectionReason.trim();
    }

    Instant now = clock.instant();
    UUID adminId = principal.subject();
    kycDocs.updateStatus(docId, newStatus, reason, adminId, now, now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("document_id", docId.toString());
    data.put("document_type", doc.documentType());
    data.put("status", newStatus);
    data.put("rejection_reason", reason);
    data.put("verified_by", adminId.toString());
    data.put("verified_at", now.toString());
    return data;
  }

  // ─── Internal helpers ────────────────────────────────────────────────────────

  private PharmacyRecord requirePharmacy(UUID pharmacyId) {
    return pharmacies
        .findById(pharmacyId)
        .orElseThrow(() -> new AppException("UNAUTHORIZED", "Pharmacy not found", 401));
  }

  private static void requireOwner(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "No pharmacy context", 401);
    }
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException("FORBIDDEN", "Only pharmacy owners can perform this action", 403);
    }
  }

  private static void requirePharmacyRole(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "No pharmacy context", 401);
    }
    if (principal.role() != AuthRole.PHARMACY_OWNER
        && principal.role() != AuthRole.PHARMACY_STAFF) {
      throw new AppException("FORBIDDEN", "Pharmacy role required", 403);
    }
  }

  private static void requireAdminRole(MedmatePrincipal principal) {
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

  private static String validateDocumentType(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("INVALID_DOCUMENT_TYPE", "document_type is required", 400);
    }
    String type = raw.trim().toUpperCase();
    if (!VALID_DOCUMENT_TYPES.contains(type)) {
      throw new AppException(
          "INVALID_DOCUMENT_TYPE", "document_type must be one of: " + VALID_DOCUMENT_TYPES, 400);
    }
    return type;
  }

  /**
   * Claimed Content-Type must be allowed and match magic bytes (PDF / JPEG / PNG). Prevents
   * labeling arbitrary bytes as application/pdf.
   */
  static String resolveMimeType(byte[] fileBytes, String contentType) {
    String claimed = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase();
    if (!ALLOWED_MIME_TYPES.contains(claimed)) {
      throw new AppException("INVALID_FILE_TYPE", "Only PDF, JPG, and PNG files are accepted", 400);
    }
    String sniffed = sniffMime(fileBytes);
    if (sniffed == null || !sniffed.equals(claimed)) {
      throw new AppException(
          "INVALID_FILE_TYPE", "File content does not match declared Content-Type", 400);
    }
    return claimed;
  }

  static String sniffMime(byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    if (bytes.length >= 4
        && java.util.Arrays.equals(
            java.util.Arrays.copyOfRange(bytes, 0, 4), new byte[] {0x25, 0x50, 0x44, 0x46})) {
      return "application/pdf";
    }
    if (bytes.length >= 3
        && (bytes[0] & 0xFF) == 0xFF
        && (bytes[1] & 0xFF) == 0xD8
        && (bytes[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }
    if (bytes.length >= 8
        && java.util.Arrays.equals(
            java.util.Arrays.copyOfRange(bytes, 0, 8),
            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) {
      return "image/png";
    }
    return null;
  }

  private void requireRateLimit(String key, int limit, int window, String code) {
    if (!rateLimiter.tryAcquire(key, limit, window)) {
      throw limited(code, "Too many requests; try again later", key, limit, window);
    }
  }

  private AppException limited(String code, String msg, String key, int limit, int window) {
    return new AppException(code, msg, 429, rateLimiter.secondsUntilAvailable(key, limit, window));
  }

  /**
   * Returns the list of required document types for the given business type. FSSAI is mandatory for
   * PHARMACY and HOSPITAL; optional (not blocking) for CLINIC_PHARMACY.
   */
  static List<String> requiredDocumentTypes(String businessType) {
    List<String> required = new ArrayList<>();
    required.add("GSTIN_CERTIFICATE");
    required.add("DRUG_LICENCE");
    if (businessType == null || FSSAI_MANDATORY_TYPES.contains(businessType)) {
      required.add("FSSAI_CERTIFICATE");
    }
    required.add("PAN_CARD");
    // One of BANK_STATEMENT or PROPRIETOR_ID — represent as BANK_STATEMENT in the list;
    // actual check is in computeMissing
    required.add("BANK_STATEMENT");
    return required;
  }

  /**
   * Computes missing required documents. A type is "present" if there's a non-REJECTED active doc.
   * BANK_STATEMENT and PROPRIETOR_ID are OR — one satisfies both.
   */
  static List<String> computeMissing(List<KycDocumentRecord> docs, List<String> required) {
    Set<String> present = new java.util.HashSet<>();
    for (KycDocumentRecord doc : docs) {
      if (!"REJECTED".equals(doc.status())) {
        present.add(doc.documentType());
      }
    }

    // Check if at least one of the identity docs is present
    boolean identityPresent =
        present.contains("BANK_STATEMENT") || present.contains("PROPRIETOR_ID");

    List<String> missing = new ArrayList<>();
    for (String type : required) {
      // BANK_STATEMENT in required list represents the BANK_STATEMENT OR PROPRIETOR_ID requirement
      if ("BANK_STATEMENT".equals(type)) {
        if (!identityPresent) {
          missing.add("BANK_STATEMENT or PROPRIETOR_ID");
        }
      } else if (!present.contains(type)) {
        missing.add(type);
      }
    }
    return missing;
  }

  private static Map<String, Object> docToMap(
      KycDocumentRecord doc, String signedUrl, Instant urlExpiresAt) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("document_id", doc.id().toString());
    m.put("document_type", doc.documentType());
    m.put("status", doc.status());
    m.put("rejection_reason", doc.rejectionReason());
    m.put("expiry_date", doc.expiryDate() != null ? doc.expiryDate().toString() : null);
    m.put("uploaded_at", doc.createdAt().toString());
    m.put("signed_url", signedUrl);
    m.put("signed_url_expires_at", urlExpiresAt.toString());
    return m;
  }

  /** Package-private for testability. */
  static String extensionFor(String mimeType) {
    return switch (mimeType) {
      case "application/pdf" -> "pdf";
      case "image/jpeg" -> "jpg";
      case "image/png" -> "png";
      default -> "bin";
    };
  }

  private void scheduleExpiryAlerts(
      UUID docId, UUID pharmacyId, LocalDate expiryDate, String documentType, Instant now) {
    String prefix = "DRUG_LICENCE".equals(documentType) ? "DRUG_LICENCE" : "FSSAI";
    scheduleAlertIfFuture(
        docId, pharmacyId, expiryDate.minusDays(60), prefix + "_EXPIRY_REMINDER_60", now);
    scheduleAlertIfFuture(
        docId, pharmacyId, expiryDate.minusDays(30), prefix + "_EXPIRY_REMINDER_30", now);
  }

  private void scheduleAlertIfFuture(
      UUID docId, UUID pharmacyId, LocalDate alertDate, String template, Instant now) {
    Instant alertAt = alertDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    if (alertAt.isAfter(now) && !kycDocs.existsExpiryAlert(docId, template)) {
      kycDocs.insertExpiryAlert(
          new KycExpiryAlertRecord(Ids.newId(), docId, pharmacyId, alertAt, template, now));
    }
  }

  /**
   * Count of active (non-deleted, not REJECTED) documents for a pharmacy. Used by
   * registrationStatus.
   */
  public int countActiveDocuments(UUID pharmacyId) {
    return kycDocs.countByPharmacyAndStatuses(
        pharmacyId, List.of("UPLOADED", "UNDER_REVIEW", "VERIFIED"));
  }

  public int countVerifiedDocuments(UUID pharmacyId) {
    return kycDocs.countByPharmacyAndStatuses(pharmacyId, List.of("VERIFIED"));
  }

  public int countRejectedDocuments(UUID pharmacyId) {
    return kycDocs.countByPharmacyAndStatuses(pharmacyId, List.of("REJECTED"));
  }
}

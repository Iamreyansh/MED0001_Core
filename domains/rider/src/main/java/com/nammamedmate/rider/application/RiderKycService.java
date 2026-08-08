package com.nammamedmate.rider.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.rider.application.port.out.AadhaarKycPort;
import com.nammamedmate.rider.application.port.out.RiderKycDocumentStore;
import com.nammamedmate.rider.application.port.out.RiderKycDocumentStore.DocumentRecord;
import com.nammamedmate.rider.application.port.out.RiderObjectStore;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderKycService {

  static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
  static final int MAX_UPLOADS_PER_TYPE = 5;
  static final Duration SIGNED_URL_TTL = Duration.ofMinutes(15);
  static final int REVIEW_HOURS = 24;

  static final Set<String> ALLOWED_MIME_TYPES =
      Set.of("application/pdf", "image/jpeg", "image/png");
  static final Set<String> VALID_DOCUMENT_TYPES =
      Set.of(
          "DRIVING_LICENCE",
          "VEHICLE_RC",
          "VEHICLE_INSURANCE",
          "PUC_CERTIFICATE",
          "AADHAAR",
          "PAN");
  static final Set<String> REQUIRES_EXPIRY = Set.of("VEHICLE_INSURANCE", "PUC_CERTIFICATE");

  private final RiderStore riders;
  private final RiderKycDocumentStore docs;
  private final RiderObjectStore objectStore;
  private final PresignedUrlService presignedUrls;
  private final AadhaarKycPort aadhaarKyc;
  private final Clock clock;
  private final boolean aadhaarKycEnabled;

  public RiderKycService(
      RiderStore riders,
      RiderKycDocumentStore docs,
      RiderObjectStore objectStore,
      PresignedUrlService presignedUrls,
      AadhaarKycPort aadhaarKyc,
      Clock clock,
      @Value("${medmate.rider.aadhaar-kyc-enabled:false}") boolean aadhaarKycEnabled) {
    this.riders = riders;
    this.docs = docs;
    this.objectStore = objectStore;
    this.presignedUrls = presignedUrls;
    this.aadhaarKyc = aadhaarKyc;
    this.clock = clock;
    this.aadhaarKycEnabled = aadhaarKycEnabled;
  }

  @Transactional
  public Map<String, Object> uploadDocument(
      MedmatePrincipal principal,
      String documentTypeRaw,
      byte[] fileBytes,
      String contentType,
      String expiryDateRaw,
      String documentNumber) {
    UUID riderId = requireRider(principal);
    RiderRecord rider = requireRiderRecord(riderId);
    if ("BLOCKED".equals(rider.status())) {
      throw new AppException("FORBIDDEN", "Blocked riders cannot upload KYC documents", 403);
    }
    if ("APPROVED".equals(rider.kycStatus())) {
      throw new AppException("KYC_ALREADY_SUBMITTED", "KYC already approved", 409);
    }

    String documentType = validateDocumentType(documentTypeRaw);
    if (fileBytes == null || fileBytes.length == 0) {
      throw new AppException("UNSUPPORTED_FILE_FORMAT", "File is empty", 415);
    }
    if (fileBytes.length > MAX_FILE_BYTES) {
      throw new AppException("FILE_TOO_LARGE", "File exceeds 10 MB limit", 413);
    }
    String mimeType = resolveMimeType(fileBytes, contentType);

    LocalDate expiryDate = null;
    if (REQUIRES_EXPIRY.contains(documentType)) {
      if (expiryDateRaw == null || expiryDateRaw.isBlank()) {
        throw new AppException(
            "VALIDATION_ERROR", documentType + " requires expiry_date (YYYY-MM-DD)", 400);
      }
      try {
        expiryDate = LocalDate.parse(expiryDateRaw.trim());
      } catch (DateTimeParseException e) {
        throw new AppException("VALIDATION_ERROR", "expiry_date must be YYYY-MM-DD", 400);
      }
      if (!expiryDate.isAfter(LocalDate.now(clock))) {
        throw new AppException("DOCUMENT_EXPIRED", "Provided expiry_date is in the past", 422);
      }
    }

    int uploads = docs.countUploadsByRiderAndType(riderId, documentType);
    if (uploads >= MAX_UPLOADS_PER_TYPE) {
      throw new AppException(
          "UPLOAD_LIMIT_REACHED", "Already 5 uploads for this document_type", 429);
    }

    docs.findActiveByRiderAndType(riderId, documentType)
        .ifPresent(existing -> docs.softDelete(existing.id(), clock.instant()));

    UUID docId = Ids.newId();
    String ext = extensionFor(mimeType);
    // Private GuardDuty-watched prefix (not CDN-public `riders/`).
    String fileKey =
        StorageObjectKeys.key(
            StorageObjectKeys.KYC,
            "riders/" + riderId + "/" + documentType + "/" + docId + "." + ext);
    objectStore.put(fileKey, fileBytes, mimeType);
    String fileUrl = presignedUrls.createGetUrl(fileKey, SIGNED_URL_TTL).url();

    Instant now = clock.instant();
    DocumentRecord doc =
        new DocumentRecord(
            docId,
            riderId,
            documentType,
            blankToNull(documentNumber),
            fileKey,
            fileUrl,
            fileBytes.length,
            mimeType,
            expiryDate,
            false,
            "PENDING",
            null,
            now,
            null,
            null);
    docs.insert(doc);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("document_id", docId.toString());
    data.put("rider_id", riderId.toString());
    data.put("document_type", documentType);
    data.put("file_url", fileUrl);
    data.put("expiry_date", expiryDate == null ? null : expiryDate.toString());
    data.put("verification_status", "PENDING");
    data.put("uploaded_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> submitKyc(MedmatePrincipal principal) {
    UUID riderId = requireRider(principal);
    RiderRecord rider = requireRiderRecord(riderId);
    if ("BLOCKED".equals(rider.status())) {
      throw new AppException("FORBIDDEN", "Blocked riders cannot submit KYC", 403);
    }
    if ("SUBMITTED".equals(rider.kycStatus()) || "APPROVED".equals(rider.kycStatus())) {
      throw new AppException(
          "KYC_ALREADY_SUBMITTED", "KYC already in SUBMITTED or APPROVED state", 409);
    }

    List<DocumentRecord> active = docs.findActiveByRider(riderId);
    boolean hasLicence = active.stream().anyMatch(d -> "DRIVING_LICENCE".equals(d.documentType()));
    if (!hasLicence) {
      throw new AppException(
          "DRIVING_LICENCE_MISSING", "Mandatory driving licence not uploaded", 422);
    }

    LocalDate today = LocalDate.now(clock);
    for (DocumentRecord d : active) {
      if (REQUIRES_EXPIRY.contains(d.documentType())
          && d.expiryDate() != null
          && !d.expiryDate().isAfter(today)) {
        throw new AppException(
            "DOCUMENT_EXPIRED_ON_SUBMIT", d.documentType() + " expired at submit time", 422);
      }
    }

    boolean aadhaarVerified = rider.aadhaarVerified();
    if (aadhaarKycEnabled) {
      aadhaarVerified =
          active.stream()
              .filter(d -> "AADHAAR".equals(d.documentType()))
              .findFirst()
              .map(d -> aadhaarKyc.verify(riderId, d.documentNumber()))
              .orElse(false);
    }

    Instant now = clock.instant();
    RiderRecord updated =
        new RiderRecord(
            rider.id(),
            rider.name(),
            rider.phone(),
            rider.email(),
            rider.vehicleType(),
            rider.vehiclePlateNumber(),
            rider.primaryZoneId(),
            rider.status(),
            "SUBMITTED",
            now,
            rider.kycReviewedAt(),
            rider.kycReviewedBy(),
            null,
            null,
            aadhaarVerified,
            rider.avgRating(),
            rider.totalTrips(),
            rider.onTimePct(),
            rider.earningsWalletBalancePaise(),
            rider.codInHandPaise(),
            rider.dailyStreakDays(),
            rider.blockedReason(),
            rider.blockedBy(),
            rider.blockedAt(),
            rider.createdAt(),
            now);
    riders.update(updated);

    List<String> submitted = new ArrayList<>();
    for (DocumentRecord d : active) {
      submitted.add(d.documentType());
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", riderId.toString());
    data.put("kyc_status", "SUBMITTED");
    data.put("submitted_at", now.toString());
    data.put("documents_submitted", submitted);
    data.put("review_expected_by", now.plus(Duration.ofHours(REVIEW_HOURS)).toString());
    return data;
  }

  static String validateDocumentType(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("INVALID_DOCUMENT_TYPE", "document_type is required", 422);
    }
    String type = raw.trim().toUpperCase();
    if (!VALID_DOCUMENT_TYPES.contains(type)) {
      throw new AppException("INVALID_DOCUMENT_TYPE", "document_type not in allowed enum", 422);
    }
    return type;
  }

  static String resolveMimeType(byte[] fileBytes, String contentType) {
    String claimed = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase();
    if (!ALLOWED_MIME_TYPES.contains(claimed)) {
      throw new AppException("UNSUPPORTED_FILE_FORMAT", "Only JPEG, PNG, PDF accepted", 415);
    }
    String sniffed = sniffMime(fileBytes);
    if (sniffed == null || !sniffed.equals(claimed)) {
      throw new AppException(
          "UNSUPPORTED_FILE_FORMAT", "File content does not match declared type", 415);
    }
    return claimed;
  }

  static String sniffMime(byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    if (bytes.length >= 4
        && Arrays.equals(Arrays.copyOfRange(bytes, 0, 4), new byte[] {0x25, 0x50, 0x44, 0x46})) {
      return "application/pdf";
    }
    if (bytes.length >= 3
        && (bytes[0] & 0xFF) == 0xFF
        && (bytes[1] & 0xFF) == 0xD8
        && (bytes[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }
    if (bytes.length >= 8
        && Arrays.equals(
            Arrays.copyOfRange(bytes, 0, 8),
            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) {
      return "image/png";
    }
    return null;
  }

  static String extensionFor(String mimeType) {
    return switch (mimeType) {
      case "application/pdf" -> "pdf";
      case "image/jpeg" -> "jpg";
      case "image/png" -> "png";
      default -> "bin";
    };
  }

  private UUID requireRider(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.RIDER) {
      throw new AppException("UNAUTHORIZED", "Rider authentication required", 401);
    }
    return principal.subject();
  }

  private RiderRecord requireRiderRecord(UUID riderId) {
    return riders
        .findById(riderId)
        .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "Rider not found", 404));
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v.trim();
  }
}

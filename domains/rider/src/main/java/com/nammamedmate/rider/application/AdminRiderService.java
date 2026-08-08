package com.nammamedmate.rider.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.application.port.out.RiderKycDocumentStore;
import com.nammamedmate.rider.application.port.out.RiderKycDocumentStore.DocumentRecord;
import com.nammamedmate.rider.application.port.out.RiderSessionRevokePort;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.ListFilter;
import com.nammamedmate.rider.application.port.out.RiderStore.PageResult;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminRiderService {

  private static final Set<String> STATUS_FILTERS =
      Set.of("PENDING_KYC", "ACTIVE", "OFFLINE", "BLOCKED", "ALL");
  private static final Set<String> SORTS = Set.of("created_at", "submitted_at", "name");
  private static final Set<AuthRole> DECISION_ROLES =
      Set.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);
  private static final Duration ADMIN_URL_TTL = Duration.ofMinutes(15);

  private final RiderStore riders;
  private final RiderKycDocumentStore docs;
  private final PresignedUrlService presignedUrls;
  private final OutboxPublisher outbox;
  private final RiderSessionRevokePort sessionRevoke;
  private final Clock clock;

  public AdminRiderService(
      RiderStore riders,
      RiderKycDocumentStore docs,
      PresignedUrlService presignedUrls,
      OutboxPublisher outbox,
      RiderSessionRevokePort sessionRevoke,
      Clock clock) {
    this.riders = riders;
    this.docs = docs;
    this.presignedUrls = presignedUrls;
    this.outbox = outbox;
    this.sessionRevoke = sessionRevoke;
    this.clock = clock;
  }

  public record ListResult(Map<String, Object> data, PaginationMeta meta) {
    public ListResult {
      data = Map.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public ListResult list(
      MedmatePrincipal principal,
      String status,
      String sort,
      String order,
      Integer page,
      Integer limit) {
    requireDecisionRole(principal);
    String statusFilter = status == null || status.isBlank() ? "ALL" : status.trim().toUpperCase();
    if (!STATUS_FILTERS.contains(statusFilter)) {
      throw new AppException("INVALID_STATUS_FILTER", "status value not in allowed enum", 422);
    }
    String sortCol = sort == null || sort.isBlank() ? "created_at" : sort.trim().toLowerCase();
    if (!SORTS.contains(sortCol)) {
      sortCol = "created_at";
    }
    String ord = order != null && "desc".equalsIgnoreCase(order.trim()) ? "desc" : "asc";
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);

    PageResult result =
        riders.list(
            new ListFilter("ALL".equals(statusFilter) ? null : statusFilter, sortCol, ord, p, lim));

    List<Map<String, Object>> rows = new ArrayList<>();
    for (RiderRecord r : result.rows()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("rider_id", r.id().toString());
      m.put("name", r.name());
      m.put("phone", r.phone());
      m.put("email", r.email());
      m.put("vehicle_type", r.vehicleType());
      m.put("vehicle_plate_number", r.vehiclePlateNumber());
      m.put("preferred_zone_id", r.primaryZoneId() == null ? null : r.primaryZoneId().toString());
      m.put("status", r.status());
      m.put("kyc_status", r.kycStatus());
      m.put("submitted_at", r.kycSubmittedAt() == null ? null : r.kycSubmittedAt().toString());
      m.put("created_at", r.createdAt().toString());
      // ponytail: admin list also attaches 15-min GET URLs for active KYC docs
      List<Map<String, Object>> docMaps = new ArrayList<>();
      for (DocumentRecord d : docs.findActiveByRider(r.id())) {
        Map<String, Object> dm = new LinkedHashMap<>();
        dm.put("document_id", d.id().toString());
        dm.put("document_type", d.documentType());
        dm.put("file_url", presignedUrls.createGetUrl(d.fileKey(), ADMIN_URL_TTL).url());
        dm.put("verification_status", d.verificationStatus());
        docMaps.add(dm);
      }
      m.put("documents", docMaps);
      rows.add(m);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("riders", rows);
    return new ListResult(data, PaginationMeta.of(p, lim, result.total()));
  }

  @Transactional
  public Map<String, Object> approve(MedmatePrincipal principal, UUID riderId, String notes) {
    requireDecisionRole(principal);
    RiderRecord rider = requireRider(riderId);
    if (!"SUBMITTED".equals(rider.kycStatus())) {
      throw new AppException("INVALID_KYC_STATE", "KYC not in SUBMITTED state", 409);
    }
    Instant now = clock.instant();
    RiderRecord updated =
        copy(
            rider,
            "ACTIVE",
            "APPROVED",
            rider.kycSubmittedAt(),
            now,
            principal.subject(),
            null,
            null,
            rider.blockedReason(),
            rider.blockedBy(),
            rider.blockedAt(),
            now);
    riders.update(updated);

    outbox.publish(
        DomainEvent.of(
            "rider.notification.kyc_approved",
            "rider",
            riderId,
            Map.of(
                "rider_id",
                riderId.toString(),
                "template",
                "RIDER_KYC_APPROVED",
                "channels",
                List.of("PUSH"))));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", riderId.toString());
    data.put("status", "ACTIVE");
    data.put("kyc_status", "APPROVED");
    data.put("approved_by", principal.subject().toString());
    data.put("approved_at", now.toString());
    if (notes != null) {
      data.put("notes", notes);
    }
    return data;
  }

  @Transactional
  public Map<String, Object> reject(
      MedmatePrincipal principal, UUID riderId, String reason, String notes) {
    requireDecisionRole(principal);
    if (reason == null || reason.isBlank()) {
      throw new AppException("REASON_REQUIRED", "reason field is missing", 422);
    }
    RiderRecord rider = requireRider(riderId);
    if (!"SUBMITTED".equals(rider.kycStatus())) {
      throw new AppException("INVALID_KYC_STATE", "KYC not in SUBMITTED state", 409);
    }
    Instant now = clock.instant();
    RiderRecord updated =
        copy(
            rider,
            rider.status(),
            "REJECTED",
            rider.kycSubmittedAt(),
            now,
            principal.subject(),
            reason.trim(),
            notes,
            rider.blockedReason(),
            rider.blockedBy(),
            rider.blockedAt(),
            now);
    riders.update(updated);

    outbox.publish(
        DomainEvent.of(
            "rider.notification.kyc_rejected",
            "rider",
            riderId,
            Map.of(
                "rider_id",
                riderId.toString(),
                "template",
                "RIDER_KYC_REJECTED",
                "channels",
                List.of("PUSH"))));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", riderId.toString());
    data.put("kyc_status", "REJECTED");
    data.put("rejection_reason", reason.trim());
    data.put("rejection_notes", notes);
    data.put("rejected_by", principal.subject().toString());
    data.put("rejected_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> block(
      MedmatePrincipal principal, UUID riderId, String reason, String notes) {
    requireDecisionRole(principal);
    if (reason == null || reason.isBlank()) {
      throw new AppException("REASON_REQUIRED", "reason is missing", 422);
    }
    RiderRecord rider = requireRider(riderId);
    if ("BLOCKED".equals(rider.status())) {
      throw new AppException("RIDER_ALREADY_BLOCKED", "Rider already in BLOCKED state", 409);
    }
    Instant now = clock.instant();
    RiderRecord updated =
        copy(
            rider,
            "BLOCKED",
            rider.kycStatus(),
            rider.kycSubmittedAt(),
            rider.kycReviewedAt(),
            rider.kycReviewedBy(),
            rider.kycRejectionReason(),
            rider.kycRejectionNotes(),
            reason.trim(),
            principal.subject(),
            now,
            now);
    riders.update(updated);
    sessionRevoke.revokeAllForUser(riderId, now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", riderId.toString());
    data.put("status", "BLOCKED");
    data.put("blocked_by", principal.subject().toString());
    data.put("blocked_at", now.toString());
    data.put("reason", reason.trim());
    if (notes != null) {
      data.put("notes", notes);
    }
    return data;
  }

  @Transactional
  public Map<String, Object> unblock(MedmatePrincipal principal, UUID riderId, String notes) {
    requireDecisionRole(principal);
    RiderRecord rider = requireRider(riderId);
    if (!"BLOCKED".equals(rider.status())) {
      throw new AppException("RIDER_NOT_BLOCKED", "Rider is not in BLOCKED state", 409);
    }
    Instant now = clock.instant();
    RiderRecord updated =
        copy(
            rider,
            "ACTIVE",
            rider.kycStatus(),
            rider.kycSubmittedAt(),
            rider.kycReviewedAt(),
            rider.kycReviewedBy(),
            rider.kycRejectionReason(),
            rider.kycRejectionNotes(),
            null,
            null,
            null,
            now);
    riders.update(updated);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", riderId.toString());
    data.put("status", "ACTIVE");
    data.put("unblocked_by", principal.subject().toString());
    data.put("unblocked_at", now.toString());
    if (notes != null) {
      data.put("notes", notes);
    }
    return data;
  }

  private static void requireDecisionRole(MedmatePrincipal principal) {
    if (principal == null || !DECISION_ROLES.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private RiderRecord requireRider(UUID riderId) {
    return riders
        .findById(riderId)
        .orElseThrow(() -> new AppException("RIDER_NOT_FOUND", "rider_id does not exist", 404));
  }

  private static RiderRecord copy(
      RiderRecord r,
      String status,
      String kycStatus,
      Instant kycSubmittedAt,
      Instant kycReviewedAt,
      UUID kycReviewedBy,
      String rejectionReason,
      String rejectionNotes,
      String blockedReason,
      UUID blockedBy,
      Instant blockedAt,
      Instant updatedAt) {
    return new RiderRecord(
        r.id(),
        r.name(),
        r.phone(),
        r.email(),
        r.vehicleType(),
        r.vehiclePlateNumber(),
        r.primaryZoneId(),
        status,
        kycStatus,
        kycSubmittedAt,
        kycReviewedAt,
        kycReviewedBy,
        rejectionReason,
        rejectionNotes,
        r.aadhaarVerified(),
        r.avgRating(),
        r.totalTrips(),
        r.onTimePct(),
        r.earningsWalletBalancePaise(),
        r.codInHandPaise(),
        r.dailyStreakDays(),
        blockedReason,
        blockedBy,
        blockedAt,
        r.createdAt(),
        updatedAt);
  }
}

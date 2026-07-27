package com.nammamedmate.pharmacy.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminDetailRow;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminListRow;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.ListFilter;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.PageResult;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore.ZoneRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
public class AdminPharmacyStatusService {

  private static final int LIST_LIMIT = 60;
  private static final int MUTATE_LIMIT_30 = 30;
  private static final int MUTATE_LIMIT_20 = 20;
  private static final int WINDOW = 60;
  private static final int DEFAULT_PAGE_LIMIT = 50;
  private static final int MAX_PAGE_LIMIT = 200;
  private static final BigDecimal MIN_COMMISSION = new BigDecimal("3.00");
  private static final BigDecimal MAX_COMMISSION = new BigDecimal("20.00");
  private static final BigDecimal DEFAULT_COMMISSION = new BigDecimal("8.00");

  private static final Set<String> STATUSES =
      Set.of("PENDING_KYC", "KYC_SUBMITTED", "ACTIVE", "SUSPENDED", "REJECTED", "ALL");
  private static final Set<String> SORTS = Set.of("created_at", "submitted_at", "business_name");
  private static final Set<String> PLANS = Set.of("FREE", "STARTER", "GROWTH", "PRO");
  private static final Set<String> DOC_TYPES = PharmacyKycService.VALID_DOCUMENT_TYPES;
  private static final Set<String> SUSPEND_TYPES = Set.of("TEMPORARY", "PERMANENT");

  private final AdminPharmacyStore store;
  private final ZoneStore zones;
  private final AuditLogStore auditLog;
  private final AutoKycService autoKyc;
  private final RateLimiter rateLimiter;
  private final OutboxPublisher outbox;
  private final Clock clock;

  public AdminPharmacyStatusService(
      AdminPharmacyStore store,
      ZoneStore zones,
      AuditLogStore auditLog,
      AutoKycService autoKyc,
      RateLimiter rateLimiter,
      OutboxPublisher outbox,
      Clock clock) {
    this.store = store;
    this.zones = zones;
    this.auditLog = auditLog;
    this.autoKyc = autoKyc;
    this.rateLimiter = rateLimiter;
    this.outbox = outbox;
    this.clock = clock;
  }

  public record AdminListResult(Map<String, Object> data, PaginationMeta meta) {
    public AdminListResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public AdminListResult list(
      MedmatePrincipal principal,
      String status,
      UUID zoneId,
      String plan,
      Boolean isOnline,
      String search,
      String sort,
      String order,
      Integer page,
      Integer limit) {
    requireListRole(principal);
    rateLimit("admin:pharmacies:list:" + principal.subject(), LIST_LIMIT);

    String statusFilter = status == null || status.isBlank() ? "ALL" : status.trim().toUpperCase();
    if (!STATUSES.contains(statusFilter)) {
      throw new AppException(
          "VALIDATION_ERROR",
          "status must be one of: PENDING_KYC, KYC_SUBMITTED, ACTIVE, SUSPENDED, REJECTED, ALL",
          400);
    }
    if (plan != null && !plan.isBlank() && !PLANS.contains(plan.trim().toUpperCase())) {
      throw new AppException(
          "VALIDATION_ERROR", "plan must be one of: FREE, STARTER, GROWTH, PRO", 400);
    }

    boolean sortExplicit = sort != null && !sort.isBlank();
    String sortField;
    String orderField;
    if (!sortExplicit && "KYC_SUBMITTED".equals(statusFilter)) {
      sortField = "submitted_at";
      orderField = order == null || order.isBlank() ? "asc" : order.trim().toLowerCase();
    } else {
      sortField = sortExplicit ? sort.trim() : "created_at";
      orderField = order == null || order.isBlank() ? "desc" : order.trim().toLowerCase();
    }
    if (!SORTS.contains(sortField)) {
      throw new AppException(
          "VALIDATION_ERROR", "sort must be one of: created_at, submitted_at, business_name", 400);
    }
    if (!"asc".equals(orderField) && !"desc".equals(orderField)) {
      throw new AppException("VALIDATION_ERROR", "order must be asc or desc", 400);
    }

    int p = page == null || page < 1 ? 1 : page;
    int l = limit == null ? DEFAULT_PAGE_LIMIT : Math.min(Math.max(limit, 1), MAX_PAGE_LIMIT);

    PageResult result =
        store.list(
            new ListFilter(
                statusFilter,
                zoneId,
                plan == null || plan.isBlank() ? null : plan.trim().toUpperCase(),
                isOnline,
                search,
                sortField,
                orderField,
                l,
                (p - 1) * l));

    Instant now = clock.instant();
    List<Map<String, Object>> pharmacies = new ArrayList<>();
    for (AdminListRow row : result.rows()) {
      pharmacies.add(toListMap(row, now));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacies", pharmacies);
    return new AdminListResult(data, PaginationMeta.of(p, l, result.total()));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> detail(MedmatePrincipal principal, UUID pharmacyId) {
    requireDetailRole(principal);
    rateLimit("admin:pharmacies:detail:" + principal.subject(), LIST_LIMIT);

    AdminDetailRow row =
        store
            .findDetail(pharmacyId)
            .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));

    Map<String, Object> autoSummary = autoKyc.latestAutoKycSummary(pharmacyId);
    String autoStatus =
        autoSummary == null ? null : String.valueOf(autoSummary.get("overall_status"));

    Map<String, Object> kyc = new LinkedHashMap<>();
    kyc.put("submitted_at", row.kycSubmittedAt() == null ? null : row.kycSubmittedAt().toString());
    kyc.put("auto_kyc_status", autoStatus);
    kyc.put("documents_summary", store.documentStatusSummary(pharmacyId));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", row.pharmacyId().toString());
    data.put("code", row.code());
    data.put("business_name", row.businessName());
    data.put("owner_name", row.ownerName());
    data.put("phone", row.phone());
    data.put("email", row.email());
    data.put("business_type", row.businessType());
    data.put("address", row.address());
    data.put("gstin", row.gstin());
    data.put("drug_licence_number", row.drugLicenceNumber());
    data.put("fssai_number", row.fssaiNumber());
    data.put("pan_number", row.panNumber());
    data.put("status", row.status());
    data.put("plan", row.plan());
    data.put("commission_pct", row.commissionPct());
    data.put("zone_id", row.zoneId() == null ? null : row.zoneId().toString());
    data.put("is_online", row.online());
    data.put("can_reapply", row.canReapply());
    data.put("kyc", kyc);
    data.put("performance", null);
    data.put("created_at", row.createdAt() == null ? null : row.createdAt().toString());
    return data;
  }

  @Transactional
  public Map<String, Object> approve(
      MedmatePrincipal principal,
      UUID pharmacyId,
      BigDecimal commissionPct,
      UUID zoneId,
      String notes,
      String clientIp) {
    requireDecisionRole(principal);
    rateLimit("admin:pharmacies:approve:" + principal.subject(), MUTATE_LIMIT_30);

    AdminDetailRow row = requirePharmacy(pharmacyId);
    if ("ACTIVE".equals(row.status())) {
      throw new AppException("ALREADY_ACTIVE", "Pharmacy is already active", 409);
    }
    if (!"KYC_SUBMITTED".equals(row.status())) {
      throw new AppException("KYC_NOT_SUBMITTED", "Pharmacy has not submitted KYC yet", 409);
    }

    BigDecimal commission =
        commissionPct == null
            ? DEFAULT_COMMISSION
            : commissionPct.setScale(2, RoundingMode.HALF_UP);
    if (commission.compareTo(MIN_COMMISSION) < 0 || commission.compareTo(MAX_COMMISSION) > 0) {
      throw new AppException(
          "INVALID_COMMISSION_PCT", "commission_pct must be between 3.00 and 20.00", 400);
    }
    if (zoneId == null) {
      throw new AppException("INVALID_ZONE", "zone_id is required", 400);
    }
    ZoneRecord zone =
        zones
            .findById(zoneId)
            .orElseThrow(
                () ->
                    new AppException(
                        "INVALID_ZONE", "zone_id does not refer to an active zone", 400));
    if (!zone.active()) {
      throw new AppException("INVALID_ZONE", "zone_id does not refer to an active zone", 400);
    }
    if (notes != null && notes.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "notes max 500 chars", 400);
    }

    Instant now = clock.instant();
    store.approve(pharmacyId, commission, zoneId, now, now);
    audit(
        principal,
        pharmacyId,
        "KYC_APPROVED",
        Map.of(
            "commission_pct",
            commission,
            "zone_id",
            zoneId.toString(),
            "notes",
            notes == null ? "" : notes),
        clientIp,
        now);

    List<String> channels = List.of("WHATSAPP", "EMAIL");
    outbox.publish(
        DomainEvent.of(
            "pharmacy.notification.kyc_approved",
            "pharmacy",
            pharmacyId,
            Map.of(
                "pharmacy_id",
                pharmacyId.toString(),
                "channels",
                channels,
                "template",
                "PHARMACY_KYC_APPROVED")));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("status", "ACTIVE");
    data.put("is_online", true);
    data.put("commission_pct", commission);
    data.put("zone_id", zoneId.toString());
    data.put("activated_at", now.toString());
    data.put("notifications_sent", channels);
    data.put("message", "Pharmacy approved and activated successfully.");
    return data;
  }

  @Transactional
  public Map<String, Object> reject(
      MedmatePrincipal principal,
      UUID pharmacyId,
      String rejectionReason,
      String rejectionDetails,
      Boolean canReapply,
      String clientIp) {
    requireDecisionRole(principal);
    rateLimit("admin:pharmacies:reject:" + principal.subject(), MUTATE_LIMIT_30);

    AdminDetailRow row = requirePharmacy(pharmacyId);
    if ("ACTIVE".equals(row.status())) {
      throw new AppException("ALREADY_ACTIVE", "Pharmacy already active; use suspend instead", 409);
    }
    if (rejectionReason == null || rejectionReason.isBlank()) {
      throw new AppException("REJECTION_REASON_REQUIRED", "rejection_reason is required", 400);
    }
    if (rejectionReason.length() > 200) {
      throw new AppException("VALIDATION_ERROR", "rejection_reason max 200 chars", 400);
    }
    if (rejectionDetails != null && rejectionDetails.length() > 1000) {
      throw new AppException("VALIDATION_ERROR", "rejection_details max 1000 chars", 400);
    }
    if (canReapply == null) {
      throw new AppException("VALIDATION_ERROR", "can_reapply is required", 400);
    }

    Instant now = clock.instant();
    store.reject(pharmacyId, rejectionReason.trim(), rejectionDetails, canReapply, now);
    audit(
        principal,
        pharmacyId,
        "KYC_REJECTED",
        Map.of(
            "rejection_reason",
            rejectionReason.trim(),
            "rejection_details",
            rejectionDetails == null ? "" : rejectionDetails,
            "can_reapply",
            canReapply),
        clientIp,
        now);

    List<String> channels = List.of("WHATSAPP", "EMAIL");
    outbox.publish(
        DomainEvent.of(
            "pharmacy.notification.kyc_rejected",
            "pharmacy",
            pharmacyId,
            Map.of(
                "pharmacy_id",
                pharmacyId.toString(),
                "channels",
                channels,
                "template",
                "PHARMACY_KYC_REJECTED",
                "rejection_reason",
                rejectionReason.trim())));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("status", "REJECTED");
    data.put("can_reapply", canReapply);
    data.put("rejection_reason", rejectionReason.trim());
    data.put("rejected_at", now.toString());
    data.put("notifications_sent", channels);
    return data;
  }

  @Transactional
  public Map<String, Object> suspend(
      MedmatePrincipal principal,
      UUID pharmacyId,
      String reason,
      String suspendType,
      String notes,
      String clientIp) {
    requireDecisionRole(principal);
    rateLimit("admin:pharmacies:suspend:" + principal.subject(), MUTATE_LIMIT_20);

    AdminDetailRow row = requirePharmacy(pharmacyId);
    if ("SUSPENDED".equals(row.status())) {
      throw new AppException("ALREADY_SUSPENDED", "Pharmacy already in SUSPENDED status", 409);
    }
    if (reason == null || reason.isBlank()) {
      throw new AppException("REASON_REQUIRED", "reason is required", 400);
    }
    if (reason.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "reason max 500 chars", 400);
    }
    if (suspendType == null || !SUSPEND_TYPES.contains(suspendType)) {
      throw new AppException(
          "VALIDATION_ERROR", "suspend_type must be TEMPORARY or PERMANENT", 400);
    }
    if (notes != null && notes.length() > 1000) {
      throw new AppException("VALIDATION_ERROR", "notes max 1000 chars", 400);
    }

    boolean canReapply = !"PERMANENT".equals(suspendType);
    Instant now = clock.instant();
    store.suspend(pharmacyId, suspendType, canReapply, now);
    audit(
        principal,
        pharmacyId,
        "PHARMACY_SUSPENDED",
        Map.of(
            "reason",
            reason.trim(),
            "suspend_type",
            suspendType,
            "notes",
            notes == null ? "" : notes,
            "can_reapply",
            canReapply),
        clientIp,
        now);

    List<String> channels = List.of("WHATSAPP", "EMAIL");
    outbox.publish(
        DomainEvent.of(
            "pharmacy.notification.suspended",
            "pharmacy",
            pharmacyId,
            Map.of(
                "pharmacy_id",
                pharmacyId.toString(),
                "channels",
                channels,
                "template",
                "PHARMACY_SUSPENDED",
                "reason",
                reason.trim())));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("status", "SUSPENDED");
    data.put("is_online", false);
    data.put("suspend_type", suspendType);
    data.put("can_reapply", canReapply);
    data.put("suspended_at", now.toString());
    data.put("notifications_sent", channels);
    return data;
  }

  @Transactional
  public Map<String, Object> reactivate(
      MedmatePrincipal principal, UUID pharmacyId, String notes, String clientIp) {
    requireDecisionRole(principal);
    rateLimit("admin:pharmacies:reactivate:" + principal.subject(), MUTATE_LIMIT_20);

    AdminDetailRow row = requirePharmacy(pharmacyId);
    if (!"SUSPENDED".equals(row.status())) {
      throw new AppException("NOT_SUSPENDED", "Pharmacy is not in SUSPENDED status", 409);
    }
    if (notes == null || notes.isBlank()) {
      throw new AppException("NOTES_REQUIRED", "notes is required", 400);
    }
    if (notes.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "notes max 500 chars", 400);
    }

    // BR6: only admin_super may restore can_reapply after it was set false
    boolean canReapply = row.canReapply() || principal.role() == AuthRole.ADMIN_SUPER;

    Instant now = clock.instant();
    store.reactivate(pharmacyId, now, canReapply);
    audit(
        principal,
        pharmacyId,
        "PHARMACY_REACTIVATED",
        Map.of("notes", notes.trim(), "can_reapply", canReapply),
        clientIp,
        now);

    List<String> channels = List.of("WHATSAPP", "EMAIL");
    outbox.publish(
        DomainEvent.of(
            "pharmacy.notification.reactivated",
            "pharmacy",
            pharmacyId,
            Map.of(
                "pharmacy_id",
                pharmacyId.toString(),
                "channels",
                channels,
                "template",
                "PHARMACY_REACTIVATED")));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("status", "ACTIVE");
    data.put("is_online", true);
    data.put("reactivated_at", now.toString());
    data.put("notifications_sent", channels);
    return data;
  }

  @Transactional
  public Map<String, Object> requestDocuments(
      MedmatePrincipal principal,
      UUID pharmacyId,
      List<String> documentTypes,
      String message,
      String clientIp) {
    requireRequestDocsRole(principal);
    rateLimit("admin:pharmacies:request-docs:" + principal.subject(), MUTATE_LIMIT_20);

    requirePharmacy(pharmacyId);
    if (message == null || message.isBlank()) {
      throw new AppException("MESSAGE_REQUIRED", "message is required", 400);
    }
    if (message.length() > 1000) {
      throw new AppException("VALIDATION_ERROR", "message max 1000 chars", 400);
    }
    if (documentTypes == null || documentTypes.isEmpty()) {
      throw new AppException("INVALID_DOCUMENT_TYPES", "document_types is required", 400);
    }
    List<String> normalized = new ArrayList<>();
    for (String type : documentTypes) {
      if (type == null || !DOC_TYPES.contains(type)) {
        throw new AppException("INVALID_DOCUMENT_TYPES", "Unknown document type in array", 400);
      }
      normalized.add(type);
    }

    Instant now = clock.instant();
    store.resetKycSla(pharmacyId, now);
    audit(
        principal,
        pharmacyId,
        "KYC_DOCUMENTS_REQUESTED",
        Map.of("document_types", normalized, "message", message.trim()),
        clientIp,
        now);

    List<String> channels = List.of("WHATSAPP", "EMAIL", "IN_APP");
    outbox.publish(
        DomainEvent.of(
            "pharmacy.notification.docs_requested",
            "pharmacy",
            pharmacyId,
            Map.of(
                "pharmacy_id",
                pharmacyId.toString(),
                "channels",
                channels,
                "document_types",
                normalized,
                "message",
                message.trim())));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("requested_document_types", normalized);
    data.put("message", "Additional documents have been requested. Pharmacy has been notified.");
    data.put("kyc_sla_reset_at", now.toString());
    data.put("notifications_sent", channels);
    return data;
  }

  private AdminDetailRow requirePharmacy(UUID pharmacyId) {
    return store
        .findDetail(pharmacyId)
        .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));
  }

  private void audit(
      MedmatePrincipal principal,
      UUID pharmacyId,
      String action,
      Map<String, Object> payload,
      String clientIp,
      Instant at) {
    auditLog.append(
        new AuditLogRecord(
            Ids.newId(),
            "PHARMACY",
            pharmacyId,
            action,
            principal.subject(),
            principal.role().name(),
            payload,
            clientIp,
            at));
  }

  private Map<String, Object> toListMap(AdminListRow row, Instant now) {
    long ageHours = 0L;
    if (row.ageAnchor() != null) {
      ageHours = Math.max(0L, Duration.between(row.ageAnchor(), now).toHours());
    }
    String urgency = ageHours > 48 ? "HIGH" : ageHours >= 24 ? "MEDIUM" : "LOW";

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("pharmacy_id", row.pharmacyId().toString());
    m.put("code", row.code());
    m.put("business_name", row.businessName());
    m.put("owner_name", row.ownerName());
    m.put("phone", row.phone());
    m.put("zone", row.zoneName());
    m.put("status", row.status());
    m.put("plan", row.plan());
    m.put("is_online", row.online());
    m.put("submitted_at", row.submittedAt() == null ? null : row.submittedAt().toString());
    m.put("document_age_hours", ageHours);
    m.put("auto_kyc_status", row.autoKycStatus());
    m.put("urgency", urgency);
    m.put("created_at", row.createdAt() == null ? null : row.createdAt().toString());
    return m;
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private static void requireListRole(MedmatePrincipal principal) {
    requirePrincipal(principal);
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_COMPLIANCE
        && role != AuthRole.ADMIN_SUPPORT) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  private static void requireDetailRole(MedmatePrincipal principal) {
    requirePrincipal(principal);
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_COMPLIANCE
        && role != AuthRole.ADMIN_SUPPORT
        && role != AuthRole.ADMIN_FINANCE) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  private static void requireDecisionRole(MedmatePrincipal principal) {
    requirePrincipal(principal);
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException(
          "FORBIDDEN", "Only admin_super or admin_operations may decide KYC", 403);
    }
  }

  private static void requireRequestDocsRole(MedmatePrincipal principal) {
    requirePrincipal(principal);
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_COMPLIANCE) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  private static void requirePrincipal(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
  }
}

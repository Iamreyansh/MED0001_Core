package com.nammamedmate.pharmacy.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminDetailRow;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminListRow;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.DirectorySummary;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.ListFilter;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.PageResult;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyCatalogueStatsPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyCatalogueStatsPort.CatalogueStats;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.CommissionLedger;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.Performance;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.RecentOrder;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore.ZoneRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminPharmacyStatusService {

  private static final int LIST_LIMIT = 60;
  private static final int EXPORT_LIMIT = 5;
  private static final int MUTATE_LIMIT_30 = 30;
  private static final int MUTATE_LIMIT_20 = 20;
  private static final int WINDOW = 60;
  private static final int DEFAULT_PAGE_LIMIT = 50;
  private static final int MAX_PAGE_LIMIT = 200;
  private static final int EXPORT_MAX_ROWS = 10_000;
  private static final int SUMMARY_TTL_SECONDS = 300;
  private static final String SUMMARY_CACHE_KEY = "admin:pharmacies:summary";
  private static final BigDecimal MIN_COMMISSION = new BigDecimal("3.00");
  private static final BigDecimal MAX_COMMISSION = new BigDecimal("20.00");
  private static final BigDecimal DEFAULT_COMMISSION = new BigDecimal("8.00");
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private static final Set<String> STATUSES =
      Set.of("PENDING_KYC", "KYC_SUBMITTED", "ACTIVE", "SUSPENDED", "REJECTED", "ALL");
  private static final Set<String> SORTS =
      Set.of(
          "created_at",
          "submitted_at",
          "business_name",
          "gmv_today",
          "orders_today",
          "rating",
          "fill_rate");
  private static final Set<String> PLANS = Set.of("FREE", "STARTER", "GROWTH", "PRO");
  private static final Set<String> DOC_TYPES = PharmacyKycService.VALID_DOCUMENT_TYPES;
  private static final Set<String> SUSPEND_TYPES = Set.of("TEMPORARY", "PERMANENT");

  private final AdminPharmacyStore store;
  private final ZoneStore zones;
  private final AuditLogStore auditLog;
  private final AutoKycService autoKyc;
  private final PharmacyOrderMetricsPort orderMetrics;
  private final PharmacyCatalogueStatsPort catalogueStats;
  private final RateLimiter rateLimiter;
  private final OutboxPublisher outbox;
  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final ObjectProvider<StringRedisTemplate> redis;
  private final ConcurrentHashMap<String, LocalCacheEntry> localSummaryCache =
      new ConcurrentHashMap<>();

  public AdminPharmacyStatusService(
      AdminPharmacyStore store,
      ZoneStore zones,
      AuditLogStore auditLog,
      AutoKycService autoKyc,
      PharmacyOrderMetricsPort orderMetrics,
      PharmacyCatalogueStatsPort catalogueStats,
      RateLimiter rateLimiter,
      OutboxPublisher outbox,
      Clock clock,
      ObjectMapper objectMapper,
      ObjectProvider<StringRedisTemplate> redis) {
    this.store = store;
    this.zones = zones;
    this.auditLog = auditLog;
    this.autoKyc = autoKyc;
    this.orderMetrics = orderMetrics;
    this.catalogueStats = catalogueStats;
    this.rateLimiter = rateLimiter;
    this.outbox = outbox;
    this.clock = clock;
    this.objectMapper = objectMapper;
    this.redis = redis;
  }

  public record AdminListResult(Map<String, Object> data, PaginationMeta meta) {
    public AdminListResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  private record LocalCacheEntry(String json, Instant expiresAt) {}

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
    requireDirectoryRole(principal);
    rateLimit("admin:pharmacies:list:" + principal.subject(), LIST_LIMIT);

    ListFilter filter =
        buildListFilter(status, zoneId, plan, isOnline, search, sort, order, page, limit);
    int p = page == null || page < 1 ? 1 : page;
    int l = limit == null ? DEFAULT_PAGE_LIMIT : Math.min(Math.max(limit, 1), MAX_PAGE_LIMIT);

    PageResult result = store.list(filter);

    Instant now = clock.instant();
    boolean includeCommission = canSeeCommission(principal.role());
    boolean includeFinancial = canSeeFinancial(principal.role());
    List<Map<String, Object>> pharmacies = new ArrayList<>();
    for (AdminListRow row : result.rows()) {
      pharmacies.add(toListMap(row, now, includeCommission, includeFinancial));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacies", pharmacies);
    return new AdminListResult(data, PaginationMeta.of(p, l, result.total()));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> summary(MedmatePrincipal principal) {
    requireDirectoryRole(principal);
    rateLimit("admin:pharmacies:summary:" + principal.subject(), LIST_LIMIT);

    Instant now = clock.instant();
    Map<String, Object> cached = readSummaryCache(now);
    Map<String, Object> data;
    if (cached != null) {
      data = new LinkedHashMap<>(cached);
    } else {
      DirectorySummary s = store.directorySummary(now);
      data = new LinkedHashMap<>();
      data.put("total_active", s.totalActive());
      data.put("pending_kyc", s.pendingKyc());
      data.put("kyc_submitted", s.kycSubmitted());
      data.put("suspended", s.suspended());
      data.put("rejected", s.rejected());
      data.put("currently_online", s.currentlyOnline());
      data.put("gmv_today", paiseToRupees(s.gmvTodayPaise()));
      data.put("commission_today", paiseToRupees(s.commissionTodayPaise()));
      data.put("orders_today", s.ordersToday());
      data.put("payout_due", paiseToRupees(s.payoutDuePaise()));
      data.put("data_as_of", s.dataAsOf().toString());
      data.put("cache_ttl_seconds", SUMMARY_TTL_SECONDS);
      writeSummaryCache(data, now);
    }
    if (!canSeeFinancial(principal.role())) {
      data.remove("gmv_today");
      data.remove("commission_today");
      data.remove("payout_due");
    }
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> detail(MedmatePrincipal principal, UUID pharmacyId) {
    requireDirectoryRole(principal);
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

    Performance perf = orderMetrics.performance(pharmacyId);
    CommissionLedger ledger = orderMetrics.commissionLedger(pharmacyId);
    CatalogueStats cat = catalogueStats.catalogueStats(pharmacyId);
    List<RecentOrder> recent = orderMetrics.recentOrders(pharmacyId, 5);

    Map<String, Object> performance = new LinkedHashMap<>();
    performance.put("fill_rate_pct", perf.fillRatePct());
    performance.put("on_time_prep_pct", perf.onTimePrepPct());
    performance.put("cancel_rate_pct", perf.cancelRatePct());
    performance.put("avg_rating", perf.avgRating());
    performance.put("review_count", perf.reviewCount());
    performance.put("orders_30d", perf.orders30d());
    performance.put("gmv_30d", paiseToRupees(perf.gmv30dPaise()));

    Map<String, Object> commissionLedger = new LinkedHashMap<>();
    commissionLedger.put("gmv_current_period", paiseToRupees(ledger.gmvCurrentPeriodPaise()));
    commissionLedger.put("commission_earned", paiseToRupees(ledger.commissionEarnedPaise()));
    commissionLedger.put("tcs_deducted", paiseToRupees(ledger.tcsDeductedPaise()));
    commissionLedger.put("net_payable", paiseToRupees(ledger.netPayablePaise()));
    commissionLedger.put(
        "last_settlement_date",
        ledger.lastSettlementDate() == null ? null : ledger.lastSettlementDate().toString());
    commissionLedger.put(
        "next_settlement_date",
        ledger.nextSettlementDate() == null ? null : ledger.nextSettlementDate().toString());

    Map<String, Object> catalogue = new LinkedHashMap<>();
    catalogue.put("mapped_skus", cat.mappedSkus());
    catalogue.put("in_stock_skus", cat.inStockSkus());
    catalogue.put("out_of_stock_skus", cat.outOfStockSkus());

    List<Map<String, Object>> recentOrders = new ArrayList<>();
    for (RecentOrder o : recent) {
      Map<String, Object> order = new LinkedHashMap<>();
      order.put("order_id", o.orderId().toString());
      order.put("order_number", o.orderNumber());
      order.put("status", o.status());
      order.put("amount", paiseToRupees(o.amountPaise()));
      order.put("created_at", o.createdAt() == null ? null : o.createdAt().toString());
      recentOrders.add(order);
    }

    Map<String, Object> zone = null;
    if (row.zoneId() != null) {
      zone = new LinkedHashMap<>();
      zone.put("zone_id", row.zoneId().toString());
      zone.put("zone_name", row.zoneName());
    }

    boolean includeCommission = canSeeCommission(principal.role());
    boolean includeFinancial = canSeeFinancial(principal.role());

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
    data.put(
        "plan_expires_at", row.planExpiresAt() == null ? null : row.planExpiresAt().toString());
    if (includeCommission) {
      data.put("commission_pct", row.commissionPct());
    }
    data.put("zone", zone);
    data.put("zone_id", row.zoneId() == null ? null : row.zoneId().toString());
    data.put("is_online", row.online());
    data.put("can_reapply", row.canReapply());
    data.put("kyc_status", row.status());
    data.put("kyc", kyc);
    data.put("performance", performance);
    if (includeCommission) {
      data.put("commission_ledger", commissionLedger);
    }
    data.put("catalogue_stats", catalogue);
    data.put("recent_orders", recentOrders);
    data.put("created_at", row.createdAt() == null ? null : row.createdAt().toString());
    data.put("updated_at", row.updatedAt() == null ? null : row.updatedAt().toString());
    if (!includeFinancial) {
      @SuppressWarnings("unchecked")
      Map<String, Object> perfView = (Map<String, Object>) data.get("performance");
      perfView.remove("gmv_30d");
      for (Map<String, Object> order : recentOrders) {
        order.remove("amount");
      }
    }
    return data;
  }

  @Transactional(readOnly = true)
  public void export(
      MedmatePrincipal principal,
      String status,
      UUID zoneId,
      String plan,
      String search,
      OutputStream out)
      throws IOException {
    requireExportRole(principal);
    rateLimit("admin:pharmacies:export:" + principal.subject(), EXPORT_LIMIT);

    ListFilter filter =
        buildListFilter(
            status, zoneId, plan, null, search, "created_at", "desc", 1, EXPORT_MAX_ROWS);
    // export ignores page; force offset 0 and hard cap
    filter =
        new ListFilter(
            filter.status(),
            filter.zoneId(),
            filter.plan(),
            filter.online(),
            filter.search(),
            filter.sort(),
            filter.order(),
            EXPORT_MAX_ROWS,
            0);

    PageResult counted =
        store.list(
            new ListFilter(
                filter.status(),
                filter.zoneId(),
                filter.plan(),
                filter.online(),
                filter.search(),
                filter.sort(),
                filter.order(),
                1,
                0));
    long totalMatching = counted.total();
    List<AdminListRow> rows = store.exportRows(filter);
    boolean truncated = totalMatching > EXPORT_MAX_ROWS;

    LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
      writer.write(
          "# Namma MedMate Pharmacy Export | "
              + day
              + " | Total rows: "
              + totalMatching
              + (truncated ? " | truncated_at=" + EXPORT_MAX_ROWS : "")
              + "\n");
      writer.write(
          "code,business_name,owner_name,phone,email,zone,status,plan,is_online,rating,orders_today,gmv_today,fill_rate_pct,commission_pct,net_payout,created_at\n");
      for (AdminListRow row : rows) {
        writer.write(csv(row.code()));
        writer.write(',');
        writer.write(csv(row.businessName()));
        writer.write(',');
        writer.write(csv(row.ownerName()));
        writer.write(',');
        writer.write(csv(row.phone()));
        writer.write(',');
        writer.write(csv(row.email()));
        writer.write(',');
        writer.write(csv(row.zoneName()));
        writer.write(',');
        writer.write(csv(row.status()));
        writer.write(',');
        writer.write(csv(row.plan()));
        writer.write(',');
        writer.write(Boolean.toString(row.online()));
        writer.write(',');
        writer.write(row.rating() == null ? "0.00" : row.rating().toPlainString());
        writer.write(',');
        writer.write(Integer.toString(row.ordersToday()));
        writer.write(',');
        writer.write(paiseToRupees(row.gmvTodayPaise()).toPlainString());
        writer.write(',');
        writer.write(row.fillRatePct() == null ? "0.00" : row.fillRatePct().toPlainString());
        writer.write(',');
        writer.write(row.commissionPct() == null ? "8.00" : row.commissionPct().toPlainString());
        writer.write(',');
        writer.write(paiseToRupees(row.netPayoutPaise()).toPlainString());
        writer.write(',');
        writer.write(
            row.createdAt() == null
                ? ""
                : LocalDate.ofInstant(row.createdAt(), ZoneOffset.UTC).toString());
        writer.write('\n');
      }
      writer.flush();
    }
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
    Map<String, Object> before = Map.of("status", row.status());
    Map<String, Object> after =
        Map.of(
            "status",
            "SUSPENDED",
            "suspended_reason",
            reason.trim(),
            "suspend_type",
            suspendType,
            "can_reapply",
            canReapply);
    audit(
        principal,
        pharmacyId,
        "pharmacy.suspend",
        Map.of(
            "before",
            before,
            "after",
            after,
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

  private ListFilter buildListFilter(
      String status,
      UUID zoneId,
      String plan,
      Boolean isOnline,
      String search,
      String sort,
      String order,
      Integer page,
      Integer limit) {
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

    String searchFilter = null;
    if (search != null && !search.isBlank()) {
      searchFilter = search.trim();
      if (searchFilter.length() < 2) {
        throw new AppException("VALIDATION_ERROR", "search requires at least 2 characters", 400);
      }
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
          "INVALID_SORT_FIELD",
          "sort must be one of: created_at, submitted_at, business_name, gmv_today, orders_today, rating, fill_rate",
          400);
    }
    if (!"asc".equals(orderField) && !"desc".equals(orderField)) {
      throw new AppException("VALIDATION_ERROR", "order must be asc or desc", 400);
    }

    int p = page == null || page < 1 ? 1 : page;
    int l = limit == null ? DEFAULT_PAGE_LIMIT : Math.min(Math.max(limit, 1), MAX_PAGE_LIMIT);

    return new ListFilter(
        statusFilter,
        zoneId,
        plan == null || plan.isBlank() ? null : plan.trim().toUpperCase(),
        isOnline,
        searchFilter,
        sortField,
        orderField,
        l,
        (p - 1) * l);
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

  private Map<String, Object> toListMap(
      AdminListRow row, Instant now, boolean includeCommission, boolean includeFinancial) {
    long ageHours = 0L;
    if (row.ageAnchor() != null) {
      ageHours = Math.max(0L, Duration.between(row.ageAnchor(), now).toHours());
    }
    String urgency = ageHours > 48 ? "HIGH" : ageHours >= 24 ? "MEDIUM" : "LOW";

    Map<String, Object> zone = null;
    if (row.zoneId() != null || row.zoneName() != null) {
      zone = new LinkedHashMap<>();
      zone.put("zone_id", row.zoneId() == null ? null : row.zoneId().toString());
      zone.put("zone_name", row.zoneName());
    }

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("pharmacy_id", row.pharmacyId().toString());
    m.put("code", row.code());
    m.put("business_name", row.businessName());
    m.put("owner_name", row.ownerName());
    m.put("phone", row.phone());
    m.put("zone", zone);
    m.put("status", row.status());
    m.put("plan", row.plan());
    m.put("is_online", row.online());
    m.put("rating", row.rating());
    m.put("review_count", row.reviewCount());
    m.put("orders_today", row.ordersToday());
    if (includeFinancial) {
      m.put("gmv_today", paiseToRupees(row.gmvTodayPaise()));
    }
    m.put("fill_rate_pct", row.fillRatePct());
    if (includeCommission) {
      m.put("commission_pct", row.commissionPct());
      m.put("net_payout", paiseToRupees(row.netPayoutPaise()));
    }
    m.put("metrics_as_of", row.metricsAsOf() == null ? null : row.metricsAsOf().toString());
    m.put("submitted_at", row.submittedAt() == null ? null : row.submittedAt().toString());
    m.put("document_age_hours", ageHours);
    m.put("auto_kyc_status", row.autoKycStatus());
    m.put("urgency", urgency);
    m.put("created_at", row.createdAt() == null ? null : row.createdAt().toString());
    return m;
  }

  private Map<String, Object> readSummaryCache(Instant now) {
    try {
      StringRedisTemplate template = redisTemplate();
      if (template != null) {
        String raw = template.opsForValue().get(SUMMARY_CACHE_KEY);
        if (raw != null && !raw.isBlank()) {
          return objectMapper.readValue(raw, MAP_TYPE);
        }
        return null;
      }
      LocalCacheEntry entry = localSummaryCache.get(SUMMARY_CACHE_KEY);
      if (entry == null || entry.expiresAt().isBefore(now)) {
        return null;
      }
      return objectMapper.readValue(entry.json(), MAP_TYPE);
    } catch (IOException | RuntimeException ex) {
      return null;
    }
  }

  private void writeSummaryCache(Map<String, Object> data, Instant now) {
    try {
      String json = objectMapper.writeValueAsString(data);
      StringRedisTemplate template = redisTemplate();
      if (template != null) {
        template
            .opsForValue()
            .set(SUMMARY_CACHE_KEY, json, Duration.ofSeconds(SUMMARY_TTL_SECONDS));
        return;
      }
      localSummaryCache.put(
          SUMMARY_CACHE_KEY, new LocalCacheEntry(json, now.plusSeconds(SUMMARY_TTL_SECONDS)));
    } catch (IOException | RuntimeException ignored) {
      // cache is best-effort
    }
  }

  private StringRedisTemplate redisTemplate() {
    return redis == null ? null : redis.getIfAvailable();
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
  }

  private static String csv(String value) {
    if (value == null) {
      return "";
    }
    if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
      return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    return value;
  }

  /**
   * Commission fields omitted for admin_support and admin_compliance (BR7). Ops/finance/super see
   * them.
   */
  static boolean canSeeCommission(AuthRole role) {
    return role == AuthRole.ADMIN_SUPER
        || role == AuthRole.ADMIN_OPERATIONS
        || role == AuthRole.ADMIN_FINANCE;
  }

  /** Broader financial metrics (GMV/payout): not for admin_compliance. */
  static boolean canSeeFinancial(AuthRole role) {
    return role != AuthRole.ADMIN_COMPLIANCE;
  }

  private static void requireDirectoryRole(MedmatePrincipal principal) {
    requirePrincipal(principal);
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_FINANCE
        && role != AuthRole.ADMIN_SUPPORT
        && role != AuthRole.ADMIN_COMPLIANCE) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  private static void requireExportRole(MedmatePrincipal principal) {
    requirePrincipal(principal);
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_FINANCE) {
      throw new AppException("FORBIDDEN", "Export not permitted for this role", 403);
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

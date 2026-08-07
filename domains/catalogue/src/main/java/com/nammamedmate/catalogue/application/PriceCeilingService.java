package com.nammamedmate.catalogue.application;

import com.nammamedmate.catalogue.application.port.out.AuditLogStore;
import com.nammamedmate.catalogue.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.catalogue.application.port.out.MedicineStore;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.MedicineRow;
import com.nammamedmate.catalogue.application.port.out.NotifyRateLimitPort;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingStore;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingStore.AboveCeilingMapping;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingStore.CeilingListResult;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingStore.CeilingRow;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingViolationStore;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingViolationStore.OpenViolation;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingViolationStore.ViolationListResult;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingViolationStore.ViolationRow;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PriceCeilingService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final Duration NOTIFY_WINDOW = Duration.ofHours(4);
  private static final int WINDOW = 60;
  private static final int LIST_LIMIT = 60;
  private static final int MUTATE_LIMIT = 20;
  private static final int NOTIFY_LIMIT = 5;
  private static final List<String> NOTIFY_CHANNELS = List.of("WHATSAPP", "IN_APP");

  private final MedicineStore medicineStore;
  private final PriceCeilingStore ceilingStore;
  private final PriceCeilingViolationStore violationStore;
  private final AuditLogStore auditLog;
  private final NotifyRateLimitPort notifyRateLimit;
  private final OutboxPublisher outbox;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public PriceCeilingService(
      MedicineStore medicineStore,
      PriceCeilingStore ceilingStore,
      PriceCeilingViolationStore violationStore,
      AuditLogStore auditLog,
      NotifyRateLimitPort notifyRateLimit,
      OutboxPublisher outbox,
      RateLimiter rateLimiter,
      Clock clock) {
    this.medicineStore = medicineStore;
    this.ceilingStore = ceilingStore;
    this.violationStore = violationStore;
    this.auditLog = auditLog;
    this.notifyRateLimit = notifyRateLimit;
    this.outbox = outbox;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record PageResult(Map<String, Object> data, PaginationMeta meta) {
    public PageResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public PageResult listCeilings(
      MedmatePrincipal principal,
      UUID categoryId,
      Boolean hasViolations,
      Integer page,
      Integer limit) {
    requireAdminReader(principal);
    rateLimit("admin:catalogue:price-ceilings:" + principal.subject(), LIST_LIMIT);
    int p = normalizePage(page);
    int l = normalizeLimit(limit);
    CeilingListResult result = ceilingStore.listCeilings(categoryId, hasViolations, p, l);
    List<Map<String, Object>> items = new ArrayList<>();
    for (CeilingRow row : result.rows()) {
      items.add(toCeilingMap(row));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("price_ceilings", items);
    return new PageResult(data, PaginationMeta.of(p, l, result.total()));
  }

  @Transactional
  public Map<String, Object> setCeiling(
      MedmatePrincipal principal,
      UUID medicineId,
      Object ceilingPrice,
      String effectiveFrom,
      String reason) {
    requireSuper(principal);
    rateLimit("admin:catalogue:price-ceiling:set:" + principal.subject(), MUTATE_LIMIT);

    MedicineRow medicine =
        medicineStore
            .findById(medicineId)
            .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));

    long ceilingPaise = parseCeilingPaise(ceilingPrice);
    if (ceilingPaise > medicine.mrpPaise()) {
      throw new AppException(
          "CEILING_ABOVE_MRP", "ceiling_price must not exceed medicine MRP", 400);
    }
    String reasonTrimmed = requireReason(reason);
    LocalDate effective = parseEffectiveFrom(effectiveFrom);
    Instant now = clock.instant();
    Long previous = medicine.mrpCeilingPaise();

    String adminName =
        ceilingStore.findAdminName(principal.subject()).orElse(principal.role().value());
    String role = principal.role().value();

    ceilingStore.setCeiling(
        medicineId,
        ceilingPaise,
        effective,
        reasonTrimmed,
        principal.subject(),
        adminName,
        role,
        now);
    syncViolationsForMedicine(medicineId, now);

    Map<String, Object> auditPayload = new LinkedHashMap<>();
    auditPayload.put("medicine_id", medicineId.toString());
    if (previous != null) {
      auditPayload.put("old_ceiling", paiseToRupees(previous));
    }
    auditPayload.put("new_ceiling", paiseToRupees(ceilingPaise));
    auditPayload.put("effective_from", effective.toString());
    auditPayload.put("reason", reasonTrimmed);
    auditPayload.put("actor_id", principal.subject().toString());
    auditLog.append(
        new AuditLogRecord(
            Ids.newId(),
            "medicine_master",
            medicineId,
            "PRICE_CEILING_SET",
            principal.subject(),
            role,
            auditPayload,
            null,
            now));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("medicine_id", medicineId.toString());
    data.put("medicine_name", medicine.name());
    data.put("previous_ceiling", previous == null ? null : paiseToRupees(previous));
    data.put("new_ceiling_price", paiseToRupees(ceilingPaise));
    data.put("mrp", paiseToRupees(medicine.mrpPaise()));
    data.put("effective_from", effective.toString());
    data.put("reason", reasonTrimmed);
    data.put("pharmacies_above_ceiling", ceilingStore.countAboveCeiling(medicineId));
    data.put("set_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> removeCeiling(
      MedmatePrincipal principal, UUID medicineId, String reason) {
    requireSuper(principal);
    rateLimit("admin:catalogue:price-ceiling:delete:" + principal.subject(), MUTATE_LIMIT);

    MedicineRow medicine =
        medicineStore
            .findById(medicineId)
            .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));
    if (medicine.mrpCeilingPaise() == null) {
      throw new AppException(
          "NO_CEILING_SET", "Medicine does not have an active price ceiling", 409);
    }
    String reasonTrimmed = requireReason(reason);
    Instant now = clock.instant();
    Long oldCeiling = medicine.mrpCeilingPaise();

    ceilingStore.clearCeiling(medicineId, now);
    int resolved = violationStore.resolveOpenForMedicine(medicineId, now);

    Map<String, Object> auditPayload = new LinkedHashMap<>();
    auditPayload.put("medicine_id", medicineId.toString());
    auditPayload.put("old_ceiling", paiseToRupees(oldCeiling));
    auditPayload.put("ceiling_cleared", true);
    auditPayload.put("effective_from", LocalDate.ofInstant(now, IST).toString());
    auditPayload.put("reason", reasonTrimmed);
    auditPayload.put("actor_id", principal.subject().toString());
    auditPayload.put("violations_resolved", resolved);
    auditLog.append(
        new AuditLogRecord(
            Ids.newId(),
            "medicine_master",
            medicineId,
            "PRICE_CEILING_REMOVED",
            principal.subject(),
            principal.role().value(),
            auditPayload,
            null,
            now));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("medicine_id", medicineId.toString());
    data.put("medicine_name", medicine.name());
    data.put("ceiling_removed", true);
    data.put("violations_resolved", resolved);
    data.put("removed_at", now.toString());
    return data;
  }

  @Transactional(readOnly = true)
  public PageResult listViolations(
      MedmatePrincipal principal, UUID medicineId, UUID zoneId, Integer page, Integer limit) {
    requireAdminReader(principal);
    rateLimit("admin:catalogue:price-violations:" + principal.subject(), LIST_LIMIT);
    int p = normalizePage(page);
    int l = normalizeLimit(limit);
    ViolationListResult result = violationStore.list(medicineId, zoneId, p, l);
    List<Map<String, Object>> items = new ArrayList<>();
    for (ViolationRow row : result.rows()) {
      items.add(toViolationMap(row));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("violations", items);
    return new PageResult(data, PaginationMeta.of(p, l, result.total()));
  }

  @Transactional
  public Map<String, Object> notifyViolations(
      MedmatePrincipal principal, UUID medicineId, String message) {
    requireNotifyRole(principal);
    rateLimit("admin:catalogue:price-violations:notify:" + principal.subject(), NOTIFY_LIMIT);

    Instant now = clock.instant();
    Optional<Instant> blocked = notifyRateLimit.tryAcquire(medicineId, NOTIFY_WINDOW, now);
    if (blocked.isPresent()) {
      throw new AppException(
          "NOTIFICATION_RATE_LIMITED",
          "Batch notification for this medicine sent within last 4 hours",
          429);
    }

    String extra = normalizeNotifyMessage(message);

    List<OpenViolation> open = violationStore.listOpen(medicineId);
    List<UUID> notifiedIds = new ArrayList<>();
    for (OpenViolation v : open) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("pharmacy_id", v.pharmacyId().toString());
      payload.put("medicine_id", v.medicineId().toString());
      payload.put("medicine_name", v.medicineName());
      payload.put("ceiling_price", paiseToRupees(v.ceilingPaise()));
      payload.put("your_current_price", paiseToRupees(v.pharmacyPricePaise()));
      payload.put("channels", NOTIFY_CHANNELS);
      payload.put("template", "PHARMACY_PRICE_CEILING_VIOLATION");
      if (extra != null) {
        payload.put("message", extra);
      }
      outbox.publish(
          DomainEvent.of(
              "catalogue.notification.price_ceiling_violation",
              "pharmacy",
              v.pharmacyId(),
              payload));
      notifiedIds.add(v.id());
    }
    violationStore.markNotified(notifiedIds, now);

    Instant nextAllowed = now.plus(NOTIFY_WINDOW);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put(
        "pharmacies_notified", open.stream().map(OpenViolation::pharmacyId).distinct().count());
    data.put("violations_covered", open.size());
    data.put("channels", NOTIFY_CHANNELS);
    data.put("notified_at", now.toString());
    data.put("next_batch_allowed_at", nextAllowed.toString());
    return data;
  }

  /** Nightly detector: UPSERT current above-ceiling online mappings; resolve stale rows. */
  @Transactional
  public void detectViolations() {
    Instant now = clock.instant();
    for (AboveCeilingMapping mapping : ceilingStore.findAllAboveCeilingMappings()) {
      violationStore.upsertOpen(
          Ids.newId(),
          mapping.medicineId(),
          mapping.pharmacyId(),
          mapping.ceilingPaise(),
          mapping.pharmacyPricePaise(),
          now);
    }
    violationStore.resolveStale(now);
  }

  private void syncViolationsForMedicine(UUID medicineId, Instant now) {
    for (AboveCeilingMapping mapping : ceilingStore.findAboveCeilingMappings(medicineId)) {
      violationStore.upsertOpen(
          Ids.newId(),
          mapping.medicineId(),
          mapping.pharmacyId(),
          mapping.ceilingPaise(),
          mapping.pharmacyPricePaise(),
          now);
    }
    violationStore.resolveStale(now);
  }

  private static Map<String, Object> toCeilingMap(CeilingRow row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("medicine_id", row.medicineId().toString());
    m.put("medicine_name", row.medicineName());
    m.put("category", row.categoryName());
    m.put("schedule", row.schedule());
    m.put("current_mrp", paiseToRupees(row.mrpPaise()));
    m.put("ceiling_price", paiseToRupees(row.ceilingPaise()));
    m.put("pharmacies_above_ceiling", row.pharmaciesAboveCeiling());
    m.put("effective_from", row.effectiveFrom() == null ? null : row.effectiveFrom().toString());
    Map<String, Object> setBy = new LinkedHashMap<>();
    setBy.put("admin_id", row.setById() == null ? null : row.setById().toString());
    setBy.put("name", row.setByName());
    setBy.put("role", row.setByRole());
    m.put("set_by", setBy);
    m.put("set_at", row.setAt() == null ? null : row.setAt().toString());
    m.put("reason", row.reason());
    return m;
  }

  private static Map<String, Object> toViolationMap(ViolationRow row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("violation_id", row.id().toString());
    m.put("medicine_id", row.medicineId().toString());
    m.put("medicine_name", row.medicineName());
    m.put("ceiling_price", paiseToRupees(row.ceilingPaise()));
    m.put("pharmacy_id", row.pharmacyId().toString());
    m.put("pharmacy_name", row.pharmacyName());
    m.put("pharmacy_price", paiseToRupees(row.pharmacyPricePaise()));
    m.put("overage_amount", paiseToRupees(row.overagePaise()));
    m.put("overage_pct", overagePct(row.overagePaise(), row.ceilingPaise()));
    m.put("zone", row.zoneName());
    m.put("detected_at", row.detectedAt() == null ? null : row.detectedAt().toString());
    m.put(
        "last_notified_at", row.lastNotifiedAt() == null ? null : row.lastNotifiedAt().toString());
    m.put("status", row.status());
    return m;
  }

  static BigDecimal overagePct(long overagePaise, long ceilingPaise) {
    if (ceilingPaise <= 0) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(overagePaise)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(ceilingPaise), 1, RoundingMode.HALF_UP);
  }

  private LocalDate parseEffectiveFrom(String raw) {
    if (raw == null) {
      return LocalDate.ofInstant(clock.instant(), IST);
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return LocalDate.ofInstant(clock.instant(), IST);
    }
    try {
      return LocalDate.parse(trimmed);
    } catch (DateTimeParseException ex) {
      throw new AppException("VALIDATION_ERROR", "effective_from must be YYYY-MM-DD", 400);
    }
  }

  private static int normalizePage(Integer page) {
    if (page == null) {
      return 1;
    }
    if (page < 1) {
      return 1;
    }
    return page;
  }

  private static int normalizeLimit(Integer limit) {
    if (limit == null) {
      return 20;
    }
    if (limit < 1) {
      return 1;
    }
    return Math.min(100, limit);
  }

  static long parseCeilingPaise(Object amount) {
    if (amount == null) {
      throw new AppException(
          "CEILING_PRICE_MUST_BE_POSITIVE", "ceiling_price must be positive", 400);
    }
    BigDecimal value;
    if (amount instanceof BigDecimal bd) {
      value = bd;
    } else if (amount instanceof Number n) {
      value = BigDecimal.valueOf(n.doubleValue());
    } else if (amount instanceof String s) {
      try {
        value = new BigDecimal(s.trim());
      } catch (NumberFormatException ex) {
        throw new AppException(
            "CEILING_PRICE_MUST_BE_POSITIVE", "ceiling_price must be positive", 400);
      }
    } else {
      throw new AppException(
          "CEILING_PRICE_MUST_BE_POSITIVE", "ceiling_price must be positive", 400);
    }
    if (value.scale() > 2) {
      throw new AppException(
          "VALIDATION_ERROR", "ceiling_price may have at most 2 decimal places", 400);
    }
    if (value.compareTo(BigDecimal.ZERO) <= 0) {
      throw new AppException(
          "CEILING_PRICE_MUST_BE_POSITIVE", "ceiling_price must be positive", 400);
    }
    return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
  }

  private static String requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new AppException("REASON_REQUIRED", "reason is required", 400);
    }
    String trimmed = reason.trim();
    if (trimmed.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "reason must be at most 500 characters", 400);
    }
    return trimmed;
  }

  private static String normalizeNotifyMessage(String message) {
    if (message == null) {
      return null;
    }
    String trimmed = message.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "message must be at most 500 characters", 400);
    }
    return trimmed;
  }

  static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private static void requireAdminReader(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_COMPLIANCE) {
      throw new AppException("FORBIDDEN", "Caller not an admin role", 403);
    }
  }

  private static void requireSuper(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Only admin_super may manage price ceilings", 403);
    }
  }

  private static void requireNotifyRole(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_COMPLIANCE) {
      throw new AppException(
          "FORBIDDEN", "Only admin_super or admin_compliance may notify pharmacies", 403);
    }
  }
}

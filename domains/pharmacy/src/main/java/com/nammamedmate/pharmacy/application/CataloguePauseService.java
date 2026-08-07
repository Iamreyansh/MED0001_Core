package com.nammamedmate.pharmacy.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.pharmacy.application.port.out.CataloguePauseStore;
import com.nammamedmate.pharmacy.application.port.out.CataloguePauseStore.CataloguePauseRow;
import com.nammamedmate.pharmacy.application.port.out.CatalogueVisibilityPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyStorefrontStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyStorefrontStore.StorefrontRow;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CataloguePauseService {

  private static final int PAUSE_LIMIT = 10;
  private static final int WINDOW = 60;
  private static final int MIN_DURATION = 1;
  private static final int MAX_DURATION = 1440;

  private final PharmacyStorefrontStore storefront;
  private final CataloguePauseStore pauseStore;
  private final CatalogueVisibilityPort visibility;
  private final AuditLogStore auditLog;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public CataloguePauseService(
      PharmacyStorefrontStore storefront,
      CataloguePauseStore pauseStore,
      CatalogueVisibilityPort visibility,
      AuditLogStore auditLog,
      RateLimiter rateLimiter,
      Clock clock) {
    this.storefront = storefront;
    this.pauseStore = pauseStore;
    this.visibility = visibility;
    this.auditLog = auditLog;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> pauseCatalogue(
      MedmatePrincipal principal,
      UUID pharmacyId,
      Integer durationMinutes,
      String reason,
      String clientIp) {
    requireDecisionRole(principal);
    rateLimit("admin:pharmacies:catalogue-pause:" + principal.subject(), PAUSE_LIMIT);

    StorefrontRow row = requireStorefront(pharmacyId);
    if (!"ACTIVE".equals(row.status())) {
      throw new AppException("PHARMACY_NOT_ACTIVE", "Pharmacy not in ACTIVE status", 409);
    }
    if (durationMinutes == null
        || durationMinutes < MIN_DURATION
        || durationMinutes > MAX_DURATION) {
      throw new AppException(
          "INVALID_DURATION", "duration_minutes must be between 1 and 1440", 400);
    }
    if (reason == null || reason.isBlank()) {
      throw new AppException("REASON_REQUIRED", "reason is required", 400);
    }
    if (reason.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "reason max 500 chars", 400);
    }
    if (pauseStore.findActivePause(pharmacyId).isPresent()) {
      throw new AppException("CATALOGUE_ALREADY_PAUSED", "Catalogue is already paused", 409);
    }

    int hiddenCount = visibility.hideAll(pharmacyId);
    Instant now = clock.instant();
    Instant autoResume = now.plus(Duration.ofMinutes(durationMinutes));
    UUID pauseId = Ids.newId();

    pauseStore.insert(
        new CataloguePauseRow(
            pauseId,
            pharmacyId,
            reason.trim(),
            now,
            autoResume,
            null,
            hiddenCount,
            principal.subject()));

    audit(
        principal,
        pharmacyId,
        "CATALOGUE_PAUSED",
        Map.of(
            "duration_minutes",
            durationMinutes,
            "reason",
            reason.trim(),
            "items_hidden_count",
            hiddenCount),
        clientIp,
        now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("catalogue_paused", true);
    data.put("pause_reason", reason.trim());
    data.put("paused_at", now.toString());
    data.put("auto_resume_at", autoResume.toString());
    data.put("items_hidden_count", hiddenCount);
    data.put("is_online", row.online());
    return data;
  }

  @Transactional
  public int resumeDuePauses() {
    Instant now = clock.instant();
    List<CataloguePauseRow> due = pauseStore.findDueForResume(now);
    for (CataloguePauseRow pause : due) {
      visibility.restoreAll(pause.pharmacyId());
      pauseStore.markResumed(pause.id(), now);
    }
    return due.size();
  }

  private StorefrontRow requireStorefront(UUID pharmacyId) {
    return storefront
        .findStorefront(pharmacyId)
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

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private static void requireDecisionRole(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Only admin_super or admin_operations may proceed", 403);
    }
  }
}

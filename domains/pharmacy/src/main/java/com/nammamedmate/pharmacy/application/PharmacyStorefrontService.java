package com.nammamedmate.pharmacy.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyStorefrontStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyStorefrontStore.StorefrontRow;
import com.nammamedmate.pharmacy.application.port.out.ZonePharmacyCachePort;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore.ZoneRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PharmacyStorefrontService {

  private static final int MUTATE_LIMIT = 30;
  private static final int ZONE_LIMIT = 20;
  private static final int WINDOW = 60;
  private static final int CUSTOMER_REFLECT_SECONDS = 5;
  private static final int ZONE_REFLECT_MINUTES = 5;

  private final PharmacyStorefrontStore storefront;
  private final ZoneStore zones;
  private final ZonePharmacyCachePort zoneCache;
  private final AuditLogStore auditLog;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public PharmacyStorefrontService(
      PharmacyStorefrontStore storefront,
      ZoneStore zones,
      ZonePharmacyCachePort zoneCache,
      AuditLogStore auditLog,
      RateLimiter rateLimiter,
      Clock clock) {
    this.storefront = storefront;
    this.zones = zones;
    this.zoneCache = zoneCache;
    this.auditLog = auditLog;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> adminToggleStorefront(
      MedmatePrincipal principal,
      UUID pharmacyId,
      Boolean isOnline,
      String reason,
      String clientIp) {
    requireDecisionRole(principal);
    rateLimit("admin:pharmacies:storefront:" + principal.subject(), MUTATE_LIMIT);
    if (isOnline == null) {
      throw new AppException("VALIDATION_ERROR", "is_online is required", 400);
    }
    if (reason != null && reason.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "reason max 500 chars", 400);
    }

    StorefrontRow row = requireStorefront(pharmacyId);
    requireActiveForStorefront(row.status());

    boolean adminForcedOffline = !isOnline;
    Instant now = clock.instant();
    storefront.updateOnlineStatus(pharmacyId, isOnline, adminForcedOffline, now);
    invalidateZoneCache(row.zoneId());

    if (reason != null && !reason.isBlank()) {
      audit(
          principal,
          pharmacyId,
          isOnline ? "STOREFRONT_ONLINE" : "STOREFRONT_OFFLINE",
          Map.of("is_online", isOnline, "reason", reason.trim()),
          clientIp,
          now);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("is_online", isOnline);
    data.put("admin_forced_offline", adminForcedOffline);
    if (reason != null && !reason.isBlank()) {
      data.put("reason", reason.trim());
    }
    data.put("changed_at", now.toString());
    data.put("cache_invalidated", true);
    data.put("customer_app_reflects_change_in_seconds", CUSTOMER_REFLECT_SECONDS);
    return data;
  }

  @Transactional
  public Map<String, Object> ownerToggleStorefront(MedmatePrincipal principal, Boolean isOnline) {
    requirePharmacyRole(principal);
    rateLimit("pharmacy:storefront:" + principal.pharmacyId(), MUTATE_LIMIT);
    if (isOnline == null) {
      throw new AppException("VALIDATION_ERROR", "is_online is required", 400);
    }

    UUID pharmacyId = principal.pharmacyId();
    StorefrontRow row = requireStorefront(pharmacyId);
    if (!"ACTIVE".equals(row.status())) {
      throw new AppException("PHARMACY_NOT_ACTIVE", "Pharmacy is not active", 403);
    }
    if (isOnline && row.adminForcedOffline()) {
      throw new AppException(
          "ADMIN_OVERRIDE_ACTIVE", "Admin has forced this pharmacy offline; contact support", 403);
    }

    Instant now = clock.instant();
    storefront.updateOnlineStatus(pharmacyId, isOnline, row.adminForcedOffline(), now);
    invalidateZoneCache(row.zoneId());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("is_online", isOnline);
    data.put("admin_forced_offline", row.adminForcedOffline());
    data.put("changed_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> reassignZone(
      MedmatePrincipal principal,
      UUID pharmacyId,
      UUID zoneId,
      Instant effectiveFrom,
      String clientIp) {
    requireDecisionRole(principal);
    rateLimit("admin:pharmacies:zone:" + principal.subject(), ZONE_LIMIT);

    StorefrontRow row = requireStorefront(pharmacyId);
    if (zoneId == null) {
      throw new AppException("INVALID_ZONE", "zone_id is required", 400);
    }
    if (zoneId.equals(row.zoneId())) {
      throw new AppException("ALREADY_IN_ZONE", "Pharmacy is already in this zone", 409);
    }

    ZoneRecord newZone =
        zones
            .findById(zoneId)
            .orElseThrow(
                () -> new AppException("INVALID_ZONE", "zone_id not found or not active", 400));
    if (!newZone.active()) {
      throw new AppException("INVALID_ZONE", "zone_id not found or not active", 400);
    }

    ZoneRecord oldZone = row.zoneId() == null ? null : zones.findById(row.zoneId()).orElse(null);

    Instant effective = effectiveFrom == null ? clock.instant() : effectiveFrom;
    Instant now = clock.instant();
    storefront.updateZone(pharmacyId, zoneId, now);

    if (row.zoneId() != null) {
      zoneCache.invalidate(row.zoneId());
    }
    zoneCache.invalidate(zoneId);

    audit(
        principal,
        pharmacyId,
        "ZONE_REASSIGNED",
        Map.of(
            "old_zone_id",
            row.zoneId() == null ? "" : row.zoneId().toString(),
            "new_zone_id",
            zoneId.toString(),
            "actor_id",
            principal.subject().toString()),
        clientIp,
        now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("previous_zone_id", row.zoneId() == null ? null : row.zoneId().toString());
    data.put("previous_zone_name", oldZone == null ? null : oldZone.name());
    data.put("new_zone_id", zoneId.toString());
    data.put("new_zone_name", newZone.name());
    data.put("effective_from", effective.toString());
    data.put("cache_invalidation_triggered", true);
    data.put("customer_app_reflects_change_in_minutes", ZONE_REFLECT_MINUTES);
    return data;
  }

  private StorefrontRow requireStorefront(UUID pharmacyId) {
    return storefront
        .findStorefront(pharmacyId)
        .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));
  }

  private static void requireActiveForStorefront(String status) {
    if (!"ACTIVE".equals(status)) {
      throw new AppException(
          "PHARMACY_NOT_ACTIVE", "Pharmacy not in ACTIVE status; cannot toggle storefront", 409);
    }
  }

  private void invalidateZoneCache(UUID zoneId) {
    if (zoneId != null) {
      zoneCache.invalidate(zoneId);
    }
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
    requirePrincipal(principal);
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Only admin_super or admin_operations may proceed", 403);
    }
  }

  static void requirePharmacyRole(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException("FORBIDDEN", "Pharmacy owner role required", 403);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "Pharmacy context missing", 401);
    }
  }

  private static void requirePrincipal(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
  }
}

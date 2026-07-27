package com.nammamedmate.pharmacy.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore;
import com.nammamedmate.pharmacy.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.ProfileRecord;
import com.nammamedmate.pharmacy.domain.IndianPhone;
import com.nammamedmate.pharmacy.domain.LogoUrlValidator;
import com.nammamedmate.pharmacy.domain.OperatingHoursValidator;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminPharmacyProfileService {

  private static final int MUTATE_LIMIT = 20;
  private static final int WINDOW = 60;

  private final PharmacyProfileStore profiles;
  private final AuditLogStore auditLog;
  private final PharmacyProfileService profileService;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public AdminPharmacyProfileService(
      PharmacyProfileStore profiles,
      AuditLogStore auditLog,
      PharmacyProfileService profileService,
      RateLimiter rateLimiter,
      Clock clock) {
    this.profiles = profiles;
    this.auditLog = auditLog;
    this.profileService = profileService;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> patchProfile(
      MedmatePrincipal principal, UUID pharmacyId, Map<String, Object> body, String clientIp) {
    requireWriteRole(principal, body.containsKey("business_name"));
    rateLimit("admin:pharmacy:profile:" + principal.subject());

    ProfileRecord before =
        profiles
            .findById(pharmacyId)
            .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));

    Instant now = clock.instant();
    List<String> changed = new ArrayList<>();
    Map<String, Object> oldValues = new LinkedHashMap<>();
    Map<String, Object> newValues = new LinkedHashMap<>();

    if (body.containsKey("business_name")) {
      String name = str(body.get("business_name"));
      if (name != null && !name.equals(before.businessName())) {
        if (name.length() < 2 || name.length() > 120) {
          throw new AppException("VALIDATION_ERROR", "business_name must be 2-120 chars", 400);
        }
        track(changed, oldValues, newValues, "business_name", before.businessName(), name);
        profiles.updateBusinessName(pharmacyId, name, now);
      }
    }
    if (body.containsKey("tagline")) {
      String tagline = str(body.get("tagline"));
      track(changed, oldValues, newValues, "tagline", before.tagline(), tagline);
      profiles.updateTagline(pharmacyId, tagline, now);
    }
    if (body.containsKey("logo_url")) {
      String logo = str(body.get("logo_url"));
      LogoUrlValidator.requireValid(logo);
      track(changed, oldValues, newValues, "logo_url", before.logoUrl(), logo);
      profiles.updateLogoUrl(pharmacyId, logo, now);
    }
    if (body.containsKey("phone")) {
      String phone = parsePhone(str(body.get("phone")));
      track(changed, oldValues, newValues, "phone", before.phone(), phone);
      profiles.updatePhone(pharmacyId, phone, now);
    }
    if (body.containsKey("email")) {
      String email = str(body.get("email"));
      track(changed, oldValues, newValues, "email", before.email(), email);
      profiles.updateEmail(pharmacyId, email, now);
    }
    if (body.containsKey("address") && body.get("address") instanceof Map<?, ?> addr) {
      @SuppressWarnings("unchecked")
      Map<String, Object> merged = merge(before.address(), (Map<String, Object>) addr);
      track(changed, oldValues, newValues, "address", before.address(), merged);
      profiles.updateAddress(pharmacyId, merged, now);
    }
    if (body.containsKey("operating_hours") && body.get("operating_hours") instanceof List<?> raw) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> hours = (List<Map<String, Object>>) raw;
      OperatingHoursValidator.requireValid(hours);
      track(changed, oldValues, newValues, "operating_hours", "updated", "updated");
      profiles.replaceOperatingHours(
          pharmacyId, profileService.mapOperatingHours(pharmacyId, hours), now);
    }

    if (!changed.isEmpty()) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("changed_fields", changed);
      payload.put("old_values", oldValues);
      payload.put("new_values", newValues);
      auditLog.append(
          new AuditLogRecord(
              Ids.newId(),
              "PHARMACY",
              pharmacyId,
              "PROFILE_UPDATED",
              principal.subject(),
              principal.role().name(),
              payload,
              clientIp,
              now));
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId.toString());
    data.put("changed_fields", changed);
    data.put("message", "Profile updated successfully.");
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getBankAccount(MedmatePrincipal principal, UUID pharmacyId) {
    requireFinanceRead(principal);
    rateLimit("admin:pharmacy:bank:" + principal.subject());
    profiles
        .findById(pharmacyId)
        .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));
    return profileService.getBankAccountForAdmin(pharmacyId);
  }

  private static void track(
      List<String> changed,
      Map<String, Object> oldValues,
      Map<String, Object> newValues,
      String field,
      Object oldVal,
      Object newVal) {
    changed.add(field);
    oldValues.put(field, oldVal);
    newValues.put(field, newVal);
  }

  private static String parsePhone(String raw) {
    try {
      return IndianPhone.requireValid(raw);
    } catch (IllegalArgumentException ex) {
      throw new AppException("INVALID_PHONE", "Phone must be +91XXXXXXXXXX", 400);
    }
  }

  private static Map<String, Object> merge(Map<String, Object> current, Map<String, Object> patch) {
    Map<String, Object> merged = new LinkedHashMap<>(current == null ? Map.of() : current);
    merged.putAll(patch);
    return merged;
  }

  private static String str(Object o) {
    return o == null ? null : String.valueOf(o).trim();
  }

  private void rateLimit(String key) {
    if (!rateLimiter.tryAcquire(key, MUTATE_LIMIT, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private static void requireWriteRole(MedmatePrincipal principal, boolean hasBusinessName) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (hasBusinessName && role != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Only admin_super may change business_name", 403);
    }
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  private static void requireFinanceRead(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    // Story GET bank auth: admin_finance, admin_super (ops use admin write paths only)
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_FINANCE) {
      throw new AppException("FORBIDDEN", "Finance or super admin role required", 403);
    }
  }
}

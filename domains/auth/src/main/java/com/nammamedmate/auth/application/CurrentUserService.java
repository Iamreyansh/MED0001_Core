package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.AdminStaffRecord;
import com.nammamedmate.auth.application.port.out.AdminStaffStore;
import com.nammamedmate.auth.application.port.out.CustomerRecord;
import com.nammamedmate.auth.application.port.out.CustomerStore;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentRecord;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentStore;
import com.nammamedmate.auth.application.port.out.PharmacyRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffStore;
import com.nammamedmate.auth.application.port.out.PharmacyStore;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {

  static final int ME_LIMIT = 60;
  static final int ME_WINDOW_SECONDS = 60;

  /** Coarse pharmacy permissions until STORY-005 RBAC. */
  static final List<String> OWNER_PERMISSIONS =
      List.of("orders:read", "orders:fulfill", "inventory:*", "staff:*");

  static final List<String> STAFF_PERMISSIONS = List.of("orders:read", "orders:fulfill");

  private final CustomerStore customerStore;
  private final PharmacyStaffStore pharmacyStaffStore;
  private final PharmacyAssignmentStore assignmentStore;
  private final PharmacyStore pharmacyStore;
  private final AdminStaffStore adminStaffStore;
  private final RateLimiter rateLimiter;

  public CurrentUserService(
      CustomerStore customerStore,
      PharmacyStaffStore pharmacyStaffStore,
      PharmacyAssignmentStore assignmentStore,
      PharmacyStore pharmacyStore,
      AdminStaffStore adminStaffStore,
      RateLimiter rateLimiter) {
    this.customerStore = customerStore;
    this.pharmacyStaffStore = pharmacyStaffStore;
    this.assignmentStore = assignmentStore;
    this.pharmacyStore = pharmacyStore;
    this.adminStaffStore = adminStaffStore;
    this.rateLimiter = rateLimiter;
  }

  public Map<String, Object> me(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    String key = "auth:user:me:" + principal.subject() + ":count";
    if (!rateLimiter.tryAcquire(key, ME_LIMIT, ME_WINDOW_SECONDS)) {
      throw new AppException(
          "RATE_LIMITED",
          "Rate limit exceeded",
          429,
          rateLimiter.secondsUntilAvailable(key, ME_LIMIT, ME_WINDOW_SECONDS));
    }

    AuthRole role = principal.role();
    return switch (role) {
      case CUSTOMER -> customerMe(principal);
      case PHARMACY_OWNER, PHARMACY_STAFF -> pharmacyMe(principal);
      case ADMIN_SUPER, ADMIN_OPERATIONS, ADMIN_FINANCE, ADMIN_SUPPORT, ADMIN_COMPLIANCE ->
          adminMe(principal);
      case RIDER -> riderMe(principal);
    };
  }

  private Map<String, Object> customerMe(MedmatePrincipal principal) {
    CustomerRecord customer =
        customerStore
            .findById(principal.subject())
            .orElseThrow(() -> new AppException("UNAUTHORIZED", "User not found", 401));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", customer.id());
    data.put("role", AuthRole.CUSTOMER.value());
    data.put("phone", customer.phone());
    data.put("name", customer.name());
    data.put("avatar_url", customer.avatarUrl());
    data.put("preferred_language", customer.preferredLanguage());
    data.put("segment", customer.segment());
    data.put(
        "wallet_balance",
        java.math.BigDecimal.valueOf(customer.walletBalancePaise()).movePointLeft(2));
    data.put("loyalty_points", customer.loyaltyPoints());
    return data;
  }

  private Map<String, Object> pharmacyMe(MedmatePrincipal principal) {
    PharmacyStaffRecord staff =
        pharmacyStaffStore
            .findById(principal.subject())
            .orElseThrow(() -> new AppException("UNAUTHORIZED", "User not found", 401));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", staff.id());
    data.put("role", principal.role().value());
    data.put("name", staff.name());
    data.put("email", staff.email());
    data.put("phone", staff.phone());
    if (principal.pharmacyId() != null) {
      PharmacyRecord pharmacy =
          pharmacyStore
              .findById(principal.pharmacyId())
              .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));
      data.put(
          "active_pharmacy",
          Map.of("id", pharmacy.id(), "name", pharmacy.name() == null ? "" : pharmacy.name()));
      assignmentStore
          .findActive(staff.id(), principal.pharmacyId())
          .map(PharmacyAssignmentRecord::roleCode)
          .ifPresent(code -> data.put("role", code));
    }
    data.put(
        "permissions",
        principal.role() == AuthRole.PHARMACY_OWNER ? OWNER_PERMISSIONS : STAFF_PERMISSIONS);
    return data;
  }

  private Map<String, Object> adminMe(MedmatePrincipal principal) {
    AdminStaffRecord admin =
        adminStaffStore
            .findById(principal.subject())
            .orElseThrow(() -> new AppException("UNAUTHORIZED", "User not found", 401));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", admin.id());
    data.put("role", admin.role());
    data.put("name", admin.name());
    data.put("email", admin.email());
    data.put("mfa_enabled", admin.mfaEnabled());
    data.put("last_login_at", admin.lastLoginAt());
    return data;
  }

  private Map<String, Object> riderMe(MedmatePrincipal principal) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", principal.subject());
    data.put("role", AuthRole.RIDER.value());
    return data;
  }
}

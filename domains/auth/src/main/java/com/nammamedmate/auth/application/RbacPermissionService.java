package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.PermissionCatalogStore;
import com.nammamedmate.auth.application.port.out.PermissionRecord;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentRecord;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentStore;
import com.nammamedmate.auth.application.port.out.PharmacyRoleRecord;
import com.nammamedmate.auth.application.port.out.PharmacyRoleStore;
import com.nammamedmate.auth.application.port.out.RolePermissionCache;
import com.nammamedmate.auth.domain.AdminRoleDefinitions;
import com.nammamedmate.auth.domain.PharmacyRoleCodes;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.PermissionMatcher;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RbacPermissionService {

  private final PharmacyRoleStore roleStore;
  private final PharmacyAssignmentStore assignmentStore;
  private final PermissionCatalogStore permissionCatalogStore;
  private final RolePermissionCache cache;

  public RbacPermissionService(
      PharmacyRoleStore roleStore,
      PharmacyAssignmentStore assignmentStore,
      PermissionCatalogStore permissionCatalogStore,
      RolePermissionCache cache) {
    this.roleStore = roleStore;
    this.assignmentStore = assignmentStore;
    this.permissionCatalogStore = permissionCatalogStore;
    this.cache = cache;
  }

  @PostConstruct
  void warmCache() {
    Map<UUID, List<String>> entries = new HashMap<>();
    for (PharmacyRoleRecord role : roleStore.listSystemRoles()) {
      entries.put(role.id(), role.permissions());
    }
    cache.putAll(entries);
  }

  public void requirePermission(MedmatePrincipal principal, String required) {
    if (!hasPermission(principal, required)) {
      throw new AppException(
          "INSUFFICIENT_PERMISSIONS",
          "Insufficient permissions",
          403,
          null,
          Map.of("required_permission", required));
    }
  }

  public boolean hasPermission(MedmatePrincipal principal, String required) {
    if (principal == null || required == null || required.isBlank()) {
      return false;
    }
    AuthRole role = principal.role();
    if (role == AuthRole.ADMIN_SUPER) {
      return true;
    }
    if (isAdmin(role)) {
      return PermissionMatcher.allows(
          AdminRoleDefinitions.enforcementPermissionsFor(role.value()), required);
    }
    if (role == AuthRole.PHARMACY_OWNER || role == AuthRole.PHARMACY_STAFF) {
      return hasPharmacyPermission(principal, required);
    }
    return false;
  }

  public List<String> resolvePharmacyPermissions(MedmatePrincipal principal) {
    if (principal == null || principal.pharmacyId() == null) {
      return List.of();
    }
    if (principal.role() == AuthRole.PHARMACY_OWNER) {
      return allPharmacyPermissionStrings();
    }
    return assignmentStore
        .findActive(principal.subject(), principal.pharmacyId())
        .map(a -> permissionsForRoleCode(a.roleCode(), principal.pharmacyId()))
        .orElse(List.of());
  }

  private boolean hasPharmacyPermission(MedmatePrincipal principal, String required) {
    if (principal.pharmacyId() == null) {
      return false;
    }
    // BR6: owner gets all pharmacy-domain permissions — never admin-domain strings.
    if (!isPharmacyDomainRequirement(required)) {
      return false;
    }
    return PermissionMatcher.allows(resolvePharmacyPermissions(principal), required);
  }

  /** True when {@code required} is a permission that exists in the pharmacy catalog. */
  private boolean isPharmacyDomainRequirement(String required) {
    String need = required.trim();
    int sep = need.indexOf(':');
    if (sep <= 0 || sep == need.length() - 1) {
      return false;
    }
    String resource = need.substring(0, sep);
    String action = need.substring(sep + 1);
    for (PermissionRecord p : permissionCatalogStore.listByDomain("pharmacy")) {
      if (!p.resource().equals(resource)) {
        continue;
      }
      if ("*".equals(action) || p.action().equals(action)) {
        return true;
      }
    }
    return false;
  }

  private List<String> permissionsForRoleCode(String roleCode, UUID pharmacyId) {
    if (PharmacyRoleCodes.isOwner(roleCode)) {
      return allPharmacyPermissionStrings();
    }
    PharmacyRoleRecord role =
        roleStore
            .findSystemByCode(roleCode)
            .or(() -> roleStore.findActiveByPharmacyAndCode(pharmacyId, roleCode))
            .orElse(null);
    if (role == null) {
      return List.of();
    }
    return cachedPermissions(role);
  }

  List<String> cachedPermissions(PharmacyRoleRecord role) {
    RolePermissionCache.OptionalPermissions hit = cache.get(role.id());
    if (hit.present()) {
      return hit.permissions();
    }
    cache.put(role.id(), role.permissions());
    return role.permissions();
  }

  void refreshCache(PharmacyRoleRecord role) {
    cache.put(role.id(), role.permissions());
  }

  void invalidateCache(UUID roleId) {
    cache.invalidate(roleId);
  }

  private List<String> allPharmacyPermissionStrings() {
    Set<String> resources = new HashSet<>();
    for (PermissionRecord p : permissionCatalogStore.listByDomain("pharmacy")) {
      resources.add(p.resource());
    }
    return resources.stream().sorted().map(r -> r + ":*").toList();
  }

  static boolean isAdmin(AuthRole role) {
    return role == AuthRole.ADMIN_SUPER
        || role == AuthRole.ADMIN_OPERATIONS
        || role == AuthRole.ADMIN_FINANCE
        || role == AuthRole.ADMIN_SUPPORT
        || role == AuthRole.ADMIN_COMPLIANCE;
  }

  public PharmacyAssignmentRecord requirePharmacyAssignment(MedmatePrincipal principal) {
    if (principal == null
        || (principal.role() != AuthRole.PHARMACY_OWNER
            && principal.role() != AuthRole.PHARMACY_STAFF)) {
      throw new AppException("FORBIDDEN", "Pharmacy staff required", 403);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("FORBIDDEN", "Active pharmacy required", 403);
    }
    return assignmentStore
        .findActive(principal.subject(), principal.pharmacyId())
        .orElseThrow(() -> new AppException("FORBIDDEN", "No active pharmacy assignment", 403));
  }
}

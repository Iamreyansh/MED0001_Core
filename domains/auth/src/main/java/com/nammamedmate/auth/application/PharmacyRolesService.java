package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.PermissionCatalogStore;
import com.nammamedmate.auth.application.port.out.PermissionRecord;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentRecord;
import com.nammamedmate.auth.application.port.out.PharmacyRoleRecord;
import com.nammamedmate.auth.application.port.out.PharmacyRoleStore;
import com.nammamedmate.auth.domain.PharmacyRoleCodes;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PharmacyRolesService {

  static final int READ_LIMIT = 30;
  static final int WRITE_LIMIT = 20;
  static final int WINDOW_SECONDS = 60;

  private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,49}$");

  private final PharmacyRoleStore roleStore;
  private final PermissionCatalogStore permissionCatalogStore;
  private final RbacPermissionService rbac;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public PharmacyRolesService(
      PharmacyRoleStore roleStore,
      PermissionCatalogStore permissionCatalogStore,
      RbacPermissionService rbac,
      RateLimiter rateLimiter,
      Clock clock) {
    this.roleStore = roleStore;
    this.permissionCatalogStore = permissionCatalogStore;
    this.rbac = rbac;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public List<Map<String, Object>> listRoles(MedmatePrincipal principal) {
    PharmacyAssignmentRecord assignment = rbac.requirePharmacyAssignment(principal);
    rateLimit(principal, "list", READ_LIMIT);
    UUID pharmacyId = assignment.pharmacyId();
    List<Map<String, Object>> out = new ArrayList<>();
    for (PharmacyRoleRecord role : roleStore.listSystemRoles()) {
      out.add(toListItem(role, pharmacyId));
    }
    for (PharmacyRoleRecord role : roleStore.listCustomByPharmacy(pharmacyId)) {
      out.add(toListItem(role, pharmacyId));
    }
    return out;
  }

  @Transactional
  public Map<String, Object> createRole(
      MedmatePrincipal principal, String name, String displayName, List<String> permissions) {
    requireOwner(principal);
    rateLimit(principal, "create", WRITE_LIMIT);
    UUID pharmacyId = principal.pharmacyId();
    String normalisedName = validateName(name);
    String normalisedDisplay = validateDisplayName(displayName);
    List<String> normalisedPerms = validatePharmacyPermissions(permissions);

    if (roleStore.findSystemByCode(normalisedName).isPresent()
        || roleStore.findActiveByPharmacyAndCode(pharmacyId, normalisedName).isPresent()) {
      throw new AppException(
          "ROLE_NAME_CONFLICT", "Role name already exists in this pharmacy", 409);
    }

    Instant now = clock.instant();
    PharmacyRoleRecord saved =
        roleStore.save(
            new PharmacyRoleRecord(
                Ids.newId(),
                pharmacyId,
                normalisedName,
                normalisedDisplay,
                false,
                normalisedPerms,
                principal.subject(),
                now,
                now,
                null));
    rbac.refreshCache(saved);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", saved.id());
    data.put("name", saved.code());
    data.put("display_name", saved.displayName());
    data.put("is_system", false);
    data.put("pharmacy_id", saved.pharmacyId());
    data.put("permissions", saved.permissions());
    data.put("created_at", saved.createdAt());
    return data;
  }

  public Map<String, Object> getPermissions(MedmatePrincipal principal, String roleId) {
    rbac.requirePharmacyAssignment(principal);
    rateLimit(principal, "get-perms", READ_LIMIT);
    PharmacyRoleRecord role = resolveRole(roleId, principal.pharmacyId());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("role_id", apiId(role));
    data.put("role_name", role.code());
    data.put("is_system", role.system());
    data.put(
        "permissions",
        role.permissions().stream().map(PharmacyRolesService::permissionDetail).toList());
    return data;
  }

  @Transactional
  public Map<String, Object> updatePermissions(
      MedmatePrincipal principal, String roleId, List<String> permissions) {
    requireOwnerOrStaffManage(principal);
    rateLimit(principal, "put-perms", WRITE_LIMIT);
    if (PharmacyRoleCodes.isSystemApiId(roleId)) {
      throw new AppException("FORBIDDEN", "System roles are immutable", 403);
    }
    UUID id = parseUuid(roleId);
    PharmacyRoleRecord existing =
        roleStore
            .findById(id)
            .orElseThrow(() -> new AppException("ROLE_NOT_FOUND", "Role not found", 404));
    if (existing.system()) {
      throw new AppException("FORBIDDEN", "System roles are immutable", 403);
    }
    if (!principal.pharmacyId().equals(existing.pharmacyId())) {
      throw new AppException("FORBIDDEN", "Role belongs to another pharmacy", 403);
    }
    List<String> normalised = validatePharmacyPermissions(permissions);
    Instant now = clock.instant();
    PharmacyRoleRecord updated =
        roleStore.save(
            new PharmacyRoleRecord(
                existing.id(),
                existing.pharmacyId(),
                existing.code(),
                existing.displayName(),
                false,
                normalised,
                existing.createdBy(),
                existing.createdAt(),
                now,
                null));
    rbac.refreshCache(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("role_id", updated.id());
    data.put("role_name", updated.code());
    data.put("permissions", updated.permissions());
    data.put("updated_at", updated.updatedAt());
    return data;
  }

  @Transactional
  public void deleteRole(MedmatePrincipal principal, String roleId) {
    requireOwner(principal);
    rateLimit(principal, "delete", WRITE_LIMIT);
    if (PharmacyRoleCodes.isSystemApiId(roleId)) {
      throw new AppException("FORBIDDEN", "System roles cannot be deleted", 403);
    }
    UUID id = parseUuid(roleId);
    PharmacyRoleRecord existing =
        roleStore
            .findById(id)
            .orElseThrow(() -> new AppException("ROLE_NOT_FOUND", "Role not found", 404));
    if (existing.system()) {
      throw new AppException("FORBIDDEN", "System roles cannot be deleted", 403);
    }
    if (!principal.pharmacyId().equals(existing.pharmacyId())) {
      throw new AppException("FORBIDDEN", "Role belongs to another pharmacy", 403);
    }
    int staffCount = roleStore.countActiveStaff(existing.id(), existing.pharmacyId());
    if (staffCount > 0) {
      throw new AppException("ROLE_IN_USE", "Reassign staff before deleting this role", 409);
    }
    Instant now = clock.instant();
    roleStore.save(
        new PharmacyRoleRecord(
            existing.id(),
            existing.pharmacyId(),
            existing.code(),
            existing.displayName(),
            false,
            existing.permissions(),
            existing.createdBy(),
            existing.createdAt(),
            now,
            now));
    rbac.invalidateCache(existing.id());
  }

  private Map<String, Object> toListItem(PharmacyRoleRecord role, UUID pharmacyId) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", apiId(role));
    map.put("name", role.code());
    map.put("display_name", role.displayName());
    map.put("is_system", role.system());
    map.put("pharmacy_id", role.pharmacyId());
    map.put("permissions", role.permissions());
    UUID countPharmacy = role.system() ? pharmacyId : role.pharmacyId();
    map.put("staff_count", roleStore.countActiveStaff(role.id(), countPharmacy));
    return map;
  }

  private PharmacyRoleRecord resolveRole(String roleId, UUID pharmacyId) {
    if (PharmacyRoleCodes.isSystemApiId(roleId)) {
      String code = PharmacyRoleCodes.codeFromSystemApiId(roleId);
      PharmacyRoleRecord system = roleStore.findSystemByCode(code).orElse(null);
      if (system == null) {
        throw new AppException("ROLE_NOT_FOUND", "Role not found", 404);
      }
      return system;
    }
    UUID id = parseUuid(roleId);
    PharmacyRoleRecord role = roleStore.findById(id).orElse(null);
    if (role == null) {
      throw new AppException("ROLE_NOT_FOUND", "Role not found", 404);
    }
    if (!role.system() && !pharmacyId.equals(role.pharmacyId())) {
      throw new AppException("FORBIDDEN", "Role belongs to another pharmacy", 403);
    }
    return role;
  }

  private void requireOwner(MedmatePrincipal principal) {
    rbac.requirePharmacyAssignment(principal);
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException("FORBIDDEN", "Pharmacy owner required", 403);
    }
  }

  private void requireOwnerOrStaffManage(MedmatePrincipal principal) {
    rbac.requirePharmacyAssignment(principal);
    if (principal.role() == AuthRole.PHARMACY_OWNER) {
      return;
    }
    rbac.requirePermission(principal, "staff:manage");
  }

  private List<String> validatePharmacyPermissions(List<String> permissions) {
    if (permissions == null) {
      throw new AppException("VALIDATION_ERROR", "permissions are required", 400);
    }
    Set<String> pharmacyResources = new LinkedHashSet<>();
    Set<String> exact = new LinkedHashSet<>();
    for (PermissionRecord p : permissionCatalogStore.listByDomain("pharmacy")) {
      pharmacyResources.add(p.resource());
      exact.add(p.permission());
    }
    LinkedHashSet<String> normalised = new LinkedHashSet<>();
    for (String raw : permissions) {
      if (raw == null || raw.isBlank()) {
        throw new AppException("VALIDATION_ERROR", "permission string is blank", 400);
      }
      String perm = raw.trim();
      if ("*".equals(perm) || "*:*".equals(perm)) {
        throw new AppException(
            "VALIDATION_ERROR", "Wildcard all-access is reserved for system owner", 400);
      }
      int sep = perm.indexOf(':');
      if (sep <= 0 || sep == perm.length() - 1) {
        throw new AppException("VALIDATION_ERROR", "Unknown permission string: " + perm, 400);
      }
      String resource = perm.substring(0, sep);
      String action = perm.substring(sep + 1);
      if (!pharmacyResources.contains(resource)) {
        throw new AppException("VALIDATION_ERROR", "Unknown permission string: " + perm, 400);
      }
      if ("*".equals(action)) {
        normalised.add(resource + ":*");
        continue;
      }
      if (!exact.contains(perm)) {
        throw new AppException("VALIDATION_ERROR", "Unknown permission string: " + perm, 400);
      }
      normalised.add(perm);
    }
    return List.copyOf(normalised);
  }

  private static String validateName(String name) {
    if (name == null || name.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "name is required", 400);
    }
    String trimmed = name.trim();
    if (!NAME_PATTERN.matcher(trimmed).matches()) {
      throw new AppException(
          "VALIDATION_ERROR", "name must be snake_case and at most 50 characters", 400);
    }
    return trimmed;
  }

  private static String validateDisplayName(String displayName) {
    if (displayName == null || displayName.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "display_name is required", 400);
    }
    String trimmed = displayName.trim();
    if (trimmed.length() > 100) {
      throw new AppException(
          "VALIDATION_ERROR", "display_name must be at most 100 characters", 400);
    }
    return trimmed;
  }

  private void rateLimit(MedmatePrincipal principal, String bucket, int limit) {
    String key = "pharmacy:rbac:" + bucket + ":" + principal.subject() + ":count";
    if (!rateLimiter.tryAcquire(key, limit, WINDOW_SECONDS)) {
      throw new AppException(
          "RATE_LIMITED",
          "Rate limit exceeded",
          429,
          rateLimiter.secondsUntilAvailable(key, limit, WINDOW_SECONDS));
    }
  }

  private static UUID parseUuid(String roleId) {
    try {
      return UUID.fromString(roleId);
    } catch (RuntimeException ex) {
      throw new AppException("ROLE_NOT_FOUND", "Role not found", 404);
    }
  }

  private static Object apiId(PharmacyRoleRecord role) {
    if (role.system()) {
      return PharmacyRoleCodes.systemApiId(role.code());
    }
    return role.id();
  }

  private static Map<String, Object> permissionDetail(String permission) {
    int sep = permission.indexOf(':');
    String resource = sep < 0 ? permission : permission.substring(0, sep);
    String action = sep < 0 ? "" : permission.substring(sep + 1);
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("permission", permission);
    map.put("resource", resource);
    map.put("action", action);
    return map;
  }
}

package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.PermissionCatalogStore;
import com.nammamedmate.auth.application.port.out.PermissionRecord;
import com.nammamedmate.auth.domain.AdminRoleDefinitions;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.MedmatePrincipal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AdminRolesService {

  static final int LIMIT = 30;
  static final int WINDOW_SECONDS = 60;

  private final PermissionCatalogStore permissionCatalogStore;
  private final RateLimiter rateLimiter;

  public AdminRolesService(PermissionCatalogStore permissionCatalogStore, RateLimiter rateLimiter) {
    this.permissionCatalogStore = permissionCatalogStore;
    this.rateLimiter = rateLimiter;
  }

  public List<Map<String, Object>> listRoles(MedmatePrincipal principal) {
    requireAdmin(principal);
    rateLimit(principal, "roles");
    return AdminRoleDefinitions.ALL.stream().map(AdminRolesService::toRoleMap).toList();
  }

  public Map<String, Object> getRolePermissions(MedmatePrincipal principal, String role) {
    requireAdmin(principal);
    rateLimit(principal, "role-permissions");
    AdminRoleDefinitions.AdminRole def =
        AdminRoleDefinitions.find(role)
            .orElseThrow(() -> new AppException("ROLE_NOT_FOUND", "Admin role not found", 404));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("role", def.role());
    data.put("display_name", def.displayName());
    data.put(
        "permissions",
        def.permissions().stream().map(AdminRoleDefinitions::toPermissionObject).toList());
    data.put("permission_count", def.permissionCount());
    if (def.notes() != null) {
      data.put("notes", def.notes());
    }
    return data;
  }

  public List<Map<String, Object>> listPermissions(MedmatePrincipal principal, String resource) {
    requireAdmin(principal);
    rateLimit(principal, "permissions");
    List<PermissionRecord> rows =
        resource == null || resource.isBlank()
            ? permissionCatalogStore.listByDomain("admin")
            : permissionCatalogStore.listByDomainAndResource("admin", resource.trim());
    return rows.stream().map(AdminRolesService::toPermissionMap).toList();
  }

  public void rejectRoleMutation() {
    throw new AppException("METHOD_NOT_ALLOWED", "Admin roles are not customisable", 405);
  }

  private void rateLimit(MedmatePrincipal principal, String bucket) {
    String key = "admin:rbac:" + bucket + ":" + principal.subject() + ":count";
    if (!rateLimiter.tryAcquire(key, LIMIT, WINDOW_SECONDS)) {
      throw new AppException(
          "RATE_LIMITED",
          "Rate limit exceeded",
          429,
          rateLimiter.secondsUntilAvailable(key, LIMIT, WINDOW_SECONDS));
    }
  }

  private static void requireAdmin(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (!RbacPermissionService.isAdmin(principal.role())) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  private static Map<String, Object> toRoleMap(AdminRoleDefinitions.AdminRole role) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("role", role.role());
    map.put("display_name", role.displayName());
    map.put("description", role.description());
    map.put("is_system", role.system());
    map.put("is_customizable", role.customizable());
    map.put("permissions", role.permissions());
    map.put("permission_count", role.permissionCount());
    if (role.notes() != null) {
      map.put("notes", role.notes());
    }
    return map;
  }

  private static Map<String, Object> toPermissionMap(PermissionRecord p) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("resource", p.resource());
    map.put("action", p.action());
    map.put("permission", p.permission());
    map.put("description", p.description());
    return map;
  }
}

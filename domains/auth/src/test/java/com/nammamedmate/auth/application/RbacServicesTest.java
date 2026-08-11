package com.nammamedmate.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.auth.adapter.out.cache.RedisRolePermissionCache;
import com.nammamedmate.auth.application.port.out.PermissionCatalogStore;
import com.nammamedmate.auth.application.port.out.PermissionRecord;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentRecord;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentStore;
import com.nammamedmate.auth.application.port.out.PharmacyRoleRecord;
import com.nammamedmate.auth.application.port.out.PharmacyRoleStore;
import com.nammamedmate.auth.domain.AdminRoleDefinitions;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RbacServicesTest {

  private static final Instant NOW = Instant.parse("2026-07-26T04:00:00Z");

  private InMemoryRoleStore roles;
  private InMemoryAssignmentStore assignments;
  private CatalogStore catalog;
  private RedisRolePermissionCache cache;
  private RbacPermissionService rbac;
  private AdminRolesService adminRoles;
  private PharmacyRolesService pharmacyRoles;
  private InMemoryRateLimiter limiter;
  private Clock clock;

  private UUID pharmacyId;
  private UUID ownerId;
  private UUID staffId;
  private UUID customRoleId;

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(NOW, ZoneOffset.UTC);
    limiter = new InMemoryRateLimiter(clock);
    roles = new InMemoryRoleStore();
    assignments = new InMemoryAssignmentStore();
    catalog = new CatalogStore();
    cache = new RedisRolePermissionCache();
    seedSystemRoles();
    rbac = new RbacPermissionService(roles, assignments, catalog, cache);
    rbac.warmCache();
    adminRoles = new AdminRolesService(catalog, limiter);
    pharmacyRoles = new PharmacyRolesService(roles, catalog, rbac, limiter, clock);
    pharmacyId = Ids.newId();
    ownerId = Ids.newId();
    staffId = Ids.newId();
    assignments.put(
        new PharmacyAssignmentRecord(
            Ids.newId(), ownerId, pharmacyId, "owner", true, NOW, null, "P"));
    assignments.put(
        new PharmacyAssignmentRecord(
            Ids.newId(), staffId, pharmacyId, "cashier", true, NOW, null, "P"));
  }

  @Test
  void adminSuperListsFiveRolesIncludingWildcard() {
    MedmatePrincipal principal =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    List<Map<String, Object>> list = adminRoles.listRoles(principal);
    assertThat(list).hasSize(5);
    assertThat(list.get(0).get("role")).isEqualTo("admin_super");
    assertThat(list.get(0).get("permissions")).isEqualTo(List.of("*:*"));
    assertThat(list.get(0).get("is_customizable")).isEqualTo(false);
    assertThat(list.get(0).get("permission_count")).isNull();
    assertThat(list.get(0).get("notes")).isEqualTo(AdminRoleDefinitions.SUPER_NOTES);
    for (var def : AdminRoleDefinitions.ALL) {
      Map<String, Object> row =
          list.stream().filter(m -> def.role().equals(m.get("role"))).findFirst().orElseThrow();
      assertThat(row.get("permissions")).isEqualTo(def.permissions());
      assertThat(row.get("display_name")).isEqualTo(def.displayName());
      assertThat(row.get("is_customizable")).isEqualTo(false);
      assertThat(row.get("permission_count")).isEqualTo(def.permissionCount());
    }
  }

  @Test
  void ac1SupportRoleMatrixHasFlagAndOrdersReadNotReleasePayout() {
    MedmatePrincipal support =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    List<Map<String, Object>> list = adminRoles.listRoles(support);
    assertThat(list).hasSize(5);
    @SuppressWarnings("unchecked")
    List<String> perms =
        (List<String>)
            list.stream()
                .filter(m -> "admin_support".equals(m.get("role")))
                .findFirst()
                .orElseThrow()
                .get("permissions");
    assertThat(perms).contains("customers:flag", "orders:read");
    assertThat(perms).doesNotContain("finance:release-payout");
  }

  @Test
  void ac2SuperRolePermissionsExactWildcard() {
    MedmatePrincipal principal =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    Map<String, Object> data = adminRoles.getRolePermissions(principal, "admin_super");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> perms = (List<Map<String, Object>>) data.get("permissions");
    assertThat(perms).hasSize(1);
    assertThat(perms.get(0).get("permission")).isEqualTo("*:*");
    assertThat(data.get("notes")).isEqualTo(AdminRoleDefinitions.SUPER_NOTES);
  }

  @Test
  void ac3FinanceRolePermissionsExactlyThirteen() {
    MedmatePrincipal principal =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    Map<String, Object> data = adminRoles.getRolePermissions(principal, "admin_finance");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> perms = (List<Map<String, Object>>) data.get("permissions");
    assertThat(perms).hasSize(13);
    assertThat(data.get("permission_count")).isEqualTo(13);
    assertThat(perms.stream().map(m -> m.get("permission")).toList())
        .contains("finance:release-payout", "wallet:credit");
  }

  @Test
  void ac4PostRolePermissionsMethodNotAllowed() {
    assertThatThrownBy(() -> adminRoles.rejectRoleMutation())
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("METHOD_NOT_ALLOWED");
              assertThat(ae.httpStatus()).isEqualTo(405);
              assertThat(ae.getMessage()).containsIgnoringCase("not customisable");
            });
  }

  @Test
  void ac5UnknownRoleNotFound() {
    MedmatePrincipal principal =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> adminRoles.getRolePermissions(principal, "admin_billing"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ROLE_NOT_FOUND");
  }

  @Test
  void ac6InsufficientPermissionsIncludesRequiredPermission() {
    MedmatePrincipal support =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> rbac.requirePermission(support, "finance:release-payout"))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("INSUFFICIENT_PERMISSIONS");
              assertThat(ae.httpStatus()).isEqualTo(403);
              assertThat(ae.details())
                  .containsEntry("required_permission", "finance:release-payout");
            });
  }

  @Test
  void ac7PermissionCheckUnderTwoMs() {
    MedmatePrincipal support =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    for (int i = 0; i < 2_000; i++) {
      rbac.hasPermission(support, "orders:read");
      rbac.hasPermission(support, "finance:release-payout");
    }
    int n = 20_000;
    long start = System.nanoTime();
    for (int i = 0; i < n; i++) {
      rbac.hasPermission(support, "orders:read");
      rbac.hasPermission(support, "finance:release-payout");
    }
    double avgMsPerCheck = ((System.nanoTime() - start) / (double) (n * 2)) / 1_000_000.0;
    assertThat(avgMsPerCheck).isLessThan(2.0);
  }

  @Test
  void adminPermissionsFilterByResource() {
    MedmatePrincipal principal =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    List<Map<String, Object>> orders = adminRoles.listPermissions(principal, "orders");
    assertThat(orders).isNotEmpty();
    assertThat(orders).allMatch(m -> "orders".equals(m.get("resource")));
  }

  @Test
  void adminSupportDeniedPharmaciesSuspend() {
    MedmatePrincipal support =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    assertThat(rbac.hasPermission(support, "pharmacies:suspend")).isFalse();
    assertThatThrownBy(() -> rbac.requirePermission(support, "pharmacies:suspend"))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("INSUFFICIENT_PERMISSIONS");
              assertThat(ae.details()).containsEntry("required_permission", "pharmacies:suspend");
            });
    MedmatePrincipal superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    assertThat(rbac.hasPermission(superAdmin, "pharmacies:suspend")).isTrue();
  }

  @Test
  void enforcementUnionKeepsLiveExtras() {
    MedmatePrincipal ops =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    assertThat(rbac.hasPermission(ops, "pharmacies:suspend")).isTrue();
    assertThat(rbac.hasPermission(ops, "orders:dispatch")).isTrue();
    assertThat(AdminRoleDefinitions.permissionsFor("admin_operations"))
        .doesNotContain("pharmacies:suspend", "orders:dispatch");

    MedmatePrincipal finance =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    assertThat(rbac.hasPermission(finance, "finance:update")).isTrue();
    assertThat(rbac.hasPermission(finance, "finance:*")).isTrue();
    assertThat(AdminRoleDefinitions.permissionsFor("admin_finance"))
        .doesNotContain("finance:update", "finance:*");
  }

  @Test
  void pharmacyOwnerCreatesAndUpdatesCustomRole() {
    MedmatePrincipal owner =
        new MedmatePrincipal(ownerId, AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
    Map<String, Object> created =
        pharmacyRoles.createRole(
            owner,
            "senior_pharmacist",
            "Senior Pharmacist",
            List.of("orders:fulfill", "inventory:*"));
    customRoleId = (UUID) created.get("id");
    assertThat(created.get("pharmacy_id")).isEqualTo(pharmacyId);
    assertThat(created.get("permissions")).isEqualTo(List.of("orders:fulfill", "inventory:*"));

    Map<String, Object> updated =
        pharmacyRoles.updatePermissions(
            owner, customRoleId.toString(), List.of("orders:read", "reports:read"));
    assertThat(updated.get("permissions")).isEqualTo(List.of("orders:read", "reports:read"));
    assertThat(updated.get("updated_at")).isEqualTo(NOW);
  }

  @Test
  void pharmacyStaffCannotCreateRole() {
    MedmatePrincipal staff =
        new MedmatePrincipal(staffId, AuthRole.PHARMACY_STAFF, pharmacyId, TokenScope.FULL, "j");
    assertThatThrownBy(
            () -> pharmacyRoles.createRole(staff, "helper", "Helper", List.of("orders:read")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void deleteRoleInUseRejected() {
    MedmatePrincipal owner =
        new MedmatePrincipal(ownerId, AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
    Map<String, Object> created =
        pharmacyRoles.createRole(owner, "temp_role", "Temp", List.of("orders:read"));
    UUID roleId = (UUID) created.get("id");
    roles.staffCounts.put(roleId, 1);
    assertThatThrownBy(() -> pharmacyRoles.deleteRole(owner, roleId.toString()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ROLE_IN_USE");
    roles.staffCounts.put(roleId, 0);
    pharmacyRoles.deleteRole(owner, roleId.toString());
    assertThat(roles.findById(roleId)).isEmpty();
  }

  @Test
  void listRolesIncludesSystemAndCustom() {
    MedmatePrincipal owner =
        new MedmatePrincipal(ownerId, AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
    pharmacyRoles.createRole(owner, "custom_one", "Custom", List.of("orders:read"));
    List<Map<String, Object>> list = pharmacyRoles.listRoles(owner);
    assertThat(list.stream().anyMatch(m -> "system-owner".equals(m.get("id")))).isTrue();
    assertThat(list.stream().anyMatch(m -> "custom_one".equals(m.get("name")))).isTrue();
    Map<String, Object> perms = pharmacyRoles.getPermissions(owner, "system-manager");
    assertThat(perms.get("is_system")).isEqualTo(true);
  }

  @Test
  void adminRoleDefinitionsMatchStory() {
    assertThat(AdminRoleDefinitions.ALL).hasSize(5);
    assertThat(AdminRoleDefinitions.permissionsFor("admin_super")).containsExactly("*:*");
    assertThat(AdminRoleDefinitions.permissionsFor("admin_operations"))
        .containsExactly(
            "orders:read",
            "orders:write",
            "orders:cancel",
            "orders:assign-rider",
            "pharmacies:read",
            "pharmacies:update",
            "riders:read",
            "riders:write",
            "riders:assign",
            "riders:suspend",
            "logistics:read",
            "logistics:update",
            "catalogue:read");
    assertThat(AdminRoleDefinitions.permissionsFor("admin_finance"))
        .containsExactly(
            "finance:read",
            "finance:write",
            "finance:release-payout",
            "settlements:read",
            "settlements:process",
            "refunds:read",
            "refunds:approve",
            "refunds:reject",
            "taxes:read",
            "taxes:export",
            "analytics:finance",
            "customers:read",
            "wallet:credit");
    assertThat(AdminRoleDefinitions.permissionsFor("admin_support"))
        .containsExactly(
            "tickets:read",
            "tickets:write",
            "tickets:close",
            "disputes:read",
            "disputes:write",
            "disputes:resolve",
            "customers:read",
            "customers:notify",
            "customers:flag",
            "orders:read");
    assertThat(AdminRoleDefinitions.permissionsFor("admin_compliance"))
        .containsExactly(
            "prescriptions:read",
            "prescriptions:review",
            "prescriptions:approve",
            "prescriptions:reject",
            "compliance:read",
            "compliance:audit",
            "compliance:flag",
            "catalogue:read",
            "catalogue:update",
            "pharmacies:read",
            "kyc:read",
            "kyc:approve",
            "kyc:reject");
    assertThat(AdminRoleDefinitions.enforcementPermissionsFor("admin_compliance"))
        .contains("taxes:read", "taxes:export");
    assertThat(AdminRoleDefinitions.enforcementPermissionsFor("admin_operations"))
        .contains("pharmacies:suspend", "orders:dispatch", "orders:*", "analytics:read");
    assertThat(AdminRoleDefinitions.enforcementPermissionsFor("admin_support"))
        .contains("pharmacies:read", "finance:read");
    assertThat(AdminRoleDefinitions.liveExtras()).containsKey("admin_finance");
    assertThat(AdminRoleDefinitions.enforcementPermissionsFor("admin_finance"))
        .contains("crm:analytics");
    assertThat(AdminRoleDefinitions.enforcementPermissionsFor("admin_operations"))
        .doesNotContain("crm:analytics");
    assertThat(AdminRoleDefinitions.descriptionFor("crm:analytics")).contains("revenue analytics");
    assertThat(AdminRoleDefinitions.find("admin_super")).isPresent();
    assertThat(AdminRoleDefinitions.find("nope")).isEmpty();
    assertThat(AdminRoleDefinitions.descriptionFor("finance:release-payout")).contains("payout");
    assertThat(AdminRoleDefinitions.descriptionFor("unknown:x")).contains("unknown:x");
    assertThat(AdminRoleDefinitions.allApiPermissions()).contains("*:*", "wallet:credit");
    assertThat(AdminRoleDefinitions.toPermissionObject("nocolon"))
        .containsEntry("resource", "nocolon")
        .containsEntry("action", "");
    assertThat(AdminRoleDefinitions.toPermissionObject("a:b"))
        .containsEntry("resource", "a")
        .containsEntry("action", "b");
    assertThatThrownBy(() -> AdminRoleDefinitions.require("nope"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AdminRoleDefinitions.enforcementPermissionsFor("nope"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void coversAdminAndRbacEdgeBranches() {
    assertThatThrownBy(() -> adminRoles.listRoles(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal customer =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> adminRoles.listRoles(customer))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    MedmatePrincipal ops =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    assertThat(adminRoles.listPermissions(ops, null)).isNotEmpty();
    assertThat(adminRoles.listPermissions(ops, " ")).isNotEmpty();
    assertThat(rbac.hasPermission(ops, "orders:cancel")).isTrue();
    assertThat(rbac.hasPermission(ops, "pharmacies:suspend")).isTrue();
    assertThat(rbac.hasPermission(null, "orders:read")).isFalse();
    assertThat(rbac.hasPermission(ops, "  ")).isFalse();
    assertThat(
            rbac.hasPermission(
                new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "c"),
                "orders:read"))
        .isFalse();

    MedmatePrincipal owner =
        new MedmatePrincipal(ownerId, AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
    assertThat(rbac.hasPermission(owner, "inventory:write")).isTrue();
    assertThat(rbac.hasPermission(owner, "staff:manage")).isTrue();
    assertThat(rbac.hasPermission(owner, "pharmacies:suspend")).isFalse();
    assertThat(rbac.hasPermission(owner, "orders:cancel")).isFalse();
    assertThat(rbac.hasPermission(owner, "not-a-perm")).isFalse();
    assertThat(rbac.hasPermission(owner, "orders:")).isFalse();
    assertThat(rbac.hasPermission(owner, ":read")).isFalse();
    assertThat(rbac.hasPermission(owner, "orders:*")).isTrue();
    assertThat(rbac.resolvePharmacyPermissions(owner)).isNotEmpty();
    assertThat(rbac.resolvePharmacyPermissions(null)).isEmpty();
    assertThat(
            rbac.resolvePharmacyPermissions(
                new MedmatePrincipal(ownerId, AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j")))
        .isEmpty();

    MedmatePrincipal staff =
        new MedmatePrincipal(staffId, AuthRole.PHARMACY_STAFF, pharmacyId, TokenScope.FULL, "j");
    assertThat(rbac.hasPermission(staff, "orders:read")).isTrue();
    assertThat(rbac.hasPermission(staff, "staff:manage")).isFalse();
    MedmatePrincipal staffNoPharmacy =
        new MedmatePrincipal(staffId, AuthRole.PHARMACY_STAFF, null, TokenScope.FULL, "j");
    assertThat(rbac.hasPermission(staffNoPharmacy, "orders:read")).isFalse();
    MedmatePrincipal staffMissingAssign =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_STAFF, pharmacyId, TokenScope.FULL, "j");
    assertThat(rbac.resolvePharmacyPermissions(staffMissingAssign)).isEmpty();

    assertThatThrownBy(() -> rbac.requirePharmacyAssignment(customer))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> rbac.requirePharmacyAssignment(staffNoPharmacy))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> rbac.requirePharmacyAssignment(staffMissingAssign))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    // rate-limit paths
    InMemoryRateLimiter tight = new InMemoryRateLimiter(clock);
    AdminRolesService limitedAdmin = new AdminRolesService(catalog, tight);
    MedmatePrincipal admin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    for (int i = 0; i < AdminRolesService.LIMIT; i++) {
      limitedAdmin.listRoles(admin);
    }
    assertThatThrownBy(() -> limitedAdmin.listRoles(admin))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void coversPharmacyRoleValidationAndAuthzBranches() {
    MedmatePrincipal owner =
        new MedmatePrincipal(ownerId, AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
    MedmatePrincipal staff =
        new MedmatePrincipal(staffId, AuthRole.PHARMACY_STAFF, pharmacyId, TokenScope.FULL, "j");

    assertThatThrownBy(() -> pharmacyRoles.createRole(owner, null, "X", List.of("orders:read")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> pharmacyRoles.createRole(owner, "BadName", "X", List.of("orders:read")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> pharmacyRoles.createRole(owner, "ok", null, List.of("orders:read")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> pharmacyRoles.createRole(owner, "ok", "x".repeat(101), List.of("orders:read")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> pharmacyRoles.createRole(owner, "ok", "Ok", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                pharmacyRoles.createRole(
                    owner, "ok", "Ok", java.util.Arrays.asList("orders:read", null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> pharmacyRoles.createRole(owner, "ok", "Ok", java.util.Arrays.asList("  ")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> pharmacyRoles.createRole(owner, "ok", "Ok", List.of("*")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> pharmacyRoles.createRole(owner, "ok", "Ok", List.of("*:*")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> pharmacyRoles.createRole(owner, "ok", "Ok", List.of("bad")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> pharmacyRoles.createRole(owner, "ok", "Ok", List.of("finance:read")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> pharmacyRoles.createRole(owner, "ok", "Ok", List.of("orders:nope")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> pharmacyRoles.createRole(owner, "owner", "Dup", List.of("orders:read")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ROLE_NAME_CONFLICT");

    Map<String, Object> created =
        pharmacyRoles.createRole(owner, "dup_name", "Dup", List.of("orders:read"));
    assertThatThrownBy(
            () -> pharmacyRoles.createRole(owner, "dup_name", "Dup2", List.of("orders:read")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ROLE_NAME_CONFLICT");

    assertThatThrownBy(() -> pharmacyRoles.updatePermissions(owner, "system-owner", List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () -> pharmacyRoles.updatePermissions(owner, UUID.randomUUID().toString(), List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ROLE_NOT_FOUND");
    assertThatThrownBy(() -> pharmacyRoles.updatePermissions(owner, "not-a-uuid", List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ROLE_NOT_FOUND");

    UUID otherPharmacy = Ids.newId();
    PharmacyRoleRecord foreign =
        roles.save(
            new PharmacyRoleRecord(
                Ids.newId(),
                otherPharmacy,
                "foreign",
                "Foreign",
                false,
                List.of("orders:read"),
                null,
                NOW,
                NOW,
                null));
    assertThatThrownBy(
            () ->
                pharmacyRoles.updatePermissions(
                    owner, foreign.id().toString(), List.of("orders:read")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                pharmacyRoles.updatePermissions(
                    owner,
                    UUID.fromString("00000000-0000-0000-0001-000000000001").toString(),
                    List.of("orders:read")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(() -> pharmacyRoles.getPermissions(owner, "system-missing"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ROLE_NOT_FOUND");
    assertThatThrownBy(() -> pharmacyRoles.getPermissions(owner, UUID.randomUUID().toString()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ROLE_NOT_FOUND");
    assertThatThrownBy(() -> pharmacyRoles.getPermissions(owner, foreign.id().toString()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThat(pharmacyRoles.getPermissions(owner, created.get("id").toString()).get("role_name"))
        .isEqualTo("dup_name");

    assertThatThrownBy(() -> pharmacyRoles.deleteRole(owner, "system-owner"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> pharmacyRoles.deleteRole(owner, UUID.randomUUID().toString()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ROLE_NOT_FOUND");
    assertThatThrownBy(() -> pharmacyRoles.deleteRole(owner, foreign.id().toString()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(
            () ->
                pharmacyRoles.deleteRole(
                    owner, UUID.fromString("00000000-0000-0000-0001-000000000001").toString()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    roles.save(
        new PharmacyRoleRecord(
            UUID.fromString("00000000-0000-0000-0001-000000000004"),
            null,
            "manager",
            "Manager",
            true,
            List.of("orders:*", "inventory:*", "staff:read", "staff:manage", "reports:read"),
            null,
            NOW,
            NOW,
            null));
    rbac.refreshCache(roles.findSystemByCode("manager").orElseThrow());
    // overwrite cashier assignment role code via new staff with manager role
    UUID managerStaff = Ids.newId();
    assignments.put(
        new PharmacyAssignmentRecord(
            Ids.newId(), managerStaff, pharmacyId, "manager", true, NOW, null, "P"));
    MedmatePrincipal manager =
        new MedmatePrincipal(
            managerStaff, AuthRole.PHARMACY_STAFF, pharmacyId, TokenScope.FULL, "j");
    assertThat(
            pharmacyRoles
                .updatePermissions(manager, created.get("id").toString(), List.of("orders:fulfill"))
                .get("permissions"))
        .isEqualTo(List.of("orders:fulfill"));

    assertThatThrownBy(
            () ->
                pharmacyRoles.updatePermissions(
                    staff, created.get("id").toString(), List.of("orders:read")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INSUFFICIENT_PERMISSIONS");

    // rate limit write
    InMemoryRateLimiter tight = new InMemoryRateLimiter(clock);
    PharmacyRolesService limited = new PharmacyRolesService(roles, catalog, rbac, tight, clock);
    for (int i = 0; i < PharmacyRolesService.WRITE_LIMIT; i++) {
      limited.createRole(owner, "r" + i, "R" + i, List.of("orders:read"));
    }
    assertThatThrownBy(() -> limited.createRole(owner, "overflow", "O", List.of("orders:read")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");

    // permission string edge: trailing colon / missing action
    assertThatThrownBy(() -> pharmacyRoles.createRole(owner, "badperm", "B", List.of("orders:")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> pharmacyRoles.createRole(owner, "badperm2", "B", List.of(":read")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // getPermissions for system owner expands bare "*" detail (no colon)
    Map<String, Object> ownerPerms = pharmacyRoles.getPermissions(owner, "system-owner");
    assertThat(ownerPerms.get("permissions")).isInstanceOf(List.class);
  }

  @Test
  void rbacResolvesCustomRoleAndCacheMiss() {
    MedmatePrincipal owner =
        new MedmatePrincipal(ownerId, AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
    Map<String, Object> created =
        pharmacyRoles.createRole(owner, "flex_role", "Flex", List.of("orders:read"));
    UUID roleId = (UUID) created.get("id");
    UUID flexStaff = Ids.newId();
    assignments.put(
        new PharmacyAssignmentRecord(
            Ids.newId(), flexStaff, pharmacyId, "flex_role", true, NOW, null, "P"));
    MedmatePrincipal flex =
        new MedmatePrincipal(flexStaff, AuthRole.PHARMACY_STAFF, pharmacyId, TokenScope.FULL, "j");
    assertThat(rbac.hasPermission(flex, "orders:read")).isTrue();
    assertThat(rbac.hasPermission(flex, "inventory:write")).isFalse();

    // staff JWT with owner role code still gets all pharmacy perms via role-code path
    UUID weird = Ids.newId();
    assignments.put(
        new PharmacyAssignmentRecord(
            Ids.newId(), weird, pharmacyId, "owner", true, NOW, null, "P"));
    MedmatePrincipal weirdPrincipal =
        new MedmatePrincipal(weird, AuthRole.PHARMACY_STAFF, pharmacyId, TokenScope.FULL, "j");
    assertThat(rbac.hasPermission(weirdPrincipal, "staff:manage")).isTrue();

    // unknown role code
    UUID unknown = Ids.newId();
    assignments.put(
        new PharmacyAssignmentRecord(
            Ids.newId(), unknown, pharmacyId, "does_not_exist", true, NOW, null, "P"));
    assertThat(
            rbac.resolvePharmacyPermissions(
                new MedmatePrincipal(
                    unknown, AuthRole.PHARMACY_STAFF, pharmacyId, TokenScope.FULL, "j")))
        .isEmpty();

    // cache miss after invalidate
    rbac.invalidateCache(roleId);
    PharmacyRoleRecord role = roles.findById(roleId).orElseThrow();
    assertThat(rbac.cachedPermissions(role)).containsExactly("orders:read");

    assertThat(rbac.hasPermission(owner, null)).isFalse();
    assertThatThrownBy(() -> rbac.requirePharmacyAssignment(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThat(RbacPermissionService.isAdmin(AuthRole.ADMIN_COMPLIANCE)).isTrue();
    assertThat(RbacPermissionService.isAdmin(AuthRole.RIDER)).isFalse();

    // resolve system role by UUID (not system-* slug)
    assertThat(
            pharmacyRoles
                .getPermissions(owner, "00000000-0000-0000-0001-000000000001")
                .get("is_system"))
        .isEqualTo(true);
    assertThatThrownBy(() -> pharmacyRoles.createRole(owner, "   ", "X", List.of("orders:read")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> pharmacyRoles.createRole(owner, "blank_dn", "   ", List.of("orders:read")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void pharmacyRoleCodesHelpers() {
    assertThat(com.nammamedmate.auth.domain.PharmacyRoleCodes.isOwner("owner")).isTrue();
    assertThat(com.nammamedmate.auth.domain.PharmacyRoleCodes.isOwner("cashier")).isFalse();
    assertThat(com.nammamedmate.auth.domain.PharmacyRoleCodes.isSystemApiId(null)).isFalse();
    assertThat(com.nammamedmate.auth.domain.PharmacyRoleCodes.codeFromSystemApiId("owner"))
        .isNull();
    assertThat(com.nammamedmate.auth.domain.PharmacyRoleCodes.codeFromSystemApiId("system-owner"))
        .isEqualTo("owner");
  }

  private void seedSystemRoles() {
    roles.save(
        new PharmacyRoleRecord(
            UUID.fromString("00000000-0000-0000-0001-000000000001"),
            null,
            "owner",
            "Pharmacy Owner",
            true,
            List.of("*"),
            null,
            NOW,
            NOW,
            null));
    roles.save(
        new PharmacyRoleRecord(
            UUID.fromString("00000000-0000-0000-0001-000000000004"),
            null,
            "manager",
            "Manager",
            true,
            List.of("orders:*", "inventory:*", "staff:read", "reports:read"),
            null,
            NOW,
            NOW,
            null));
    roles.save(
        new PharmacyRoleRecord(
            UUID.fromString("00000000-0000-0000-0001-000000000003"),
            null,
            "cashier",
            "Cashier",
            true,
            List.of("orders:read", "orders:pos-create", "payments:collect"),
            null,
            NOW,
            NOW,
            null));
  }

  private static final class InMemoryRoleStore implements PharmacyRoleStore {
    private final Map<UUID, PharmacyRoleRecord> byId = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> staffCounts = new ConcurrentHashMap<>();

    @Override
    public List<PharmacyRoleRecord> listSystemRoles() {
      return byId.values().stream().filter(r -> r.system() && r.deletedAt() == null).toList();
    }

    @Override
    public List<PharmacyRoleRecord> listCustomByPharmacy(UUID pharmacyId) {
      return byId.values().stream()
          .filter(r -> !r.system() && pharmacyId.equals(r.pharmacyId()) && r.deletedAt() == null)
          .toList();
    }

    @Override
    public Optional<PharmacyRoleRecord> findById(UUID id) {
      PharmacyRoleRecord r = byId.get(id);
      if (r == null || r.deletedAt() != null) {
        return Optional.empty();
      }
      return Optional.of(r);
    }

    @Override
    public Optional<PharmacyRoleRecord> findSystemByCode(String code) {
      return byId.values().stream()
          .filter(r -> r.system() && r.deletedAt() == null && r.code().equals(code))
          .findFirst();
    }

    @Override
    public Optional<PharmacyRoleRecord> findActiveByPharmacyAndCode(UUID pharmacyId, String code) {
      return byId.values().stream()
          .filter(
              r ->
                  !r.system()
                      && pharmacyId.equals(r.pharmacyId())
                      && r.deletedAt() == null
                      && r.code().equals(code))
          .findFirst();
    }

    @Override
    public PharmacyRoleRecord save(PharmacyRoleRecord role) {
      byId.put(role.id(), role);
      return role;
    }

    @Override
    public int countActiveStaff(UUID roleId, UUID pharmacyId) {
      return staffCounts.getOrDefault(roleId, 0);
    }
  }

  private static final class InMemoryAssignmentStore implements PharmacyAssignmentStore {
    private final List<PharmacyAssignmentRecord> records = new ArrayList<>();

    void put(PharmacyAssignmentRecord r) {
      records.add(r);
    }

    @Override
    public List<PharmacyAssignmentRecord> listActiveByStaffId(UUID staffId) {
      return records.stream().filter(r -> r.staffId().equals(staffId) && r.isActive()).toList();
    }

    @Override
    public Optional<PharmacyAssignmentRecord> findActive(UUID staffId, UUID pharmacyId) {
      return records.stream()
          .filter(
              r -> r.staffId().equals(staffId) && r.pharmacyId().equals(pharmacyId) && r.isActive())
          .findFirst();
    }
  }

  private static final class CatalogStore implements PermissionCatalogStore {
    private final List<PermissionRecord> all =
        List.of(
            new PermissionRecord("orders", "read", "View order details and history", "admin"),
            new PermissionRecord("orders", "write", "Create and modify orders", "admin"),
            new PermissionRecord("orders", "cancel", "Cancel any order", "admin"),
            new PermissionRecord("pharmacies", "suspend", "Suspend pharmacy", "admin"),
            new PermissionRecord("orders", "read", "View pharmacy orders", "pharmacy"),
            new PermissionRecord("orders", "fulfill", "Fulfill", "pharmacy"),
            new PermissionRecord("inventory", "read", "Inv read", "pharmacy"),
            new PermissionRecord("inventory", "write", "Inv write", "pharmacy"),
            new PermissionRecord("reports", "read", "Reports", "pharmacy"),
            new PermissionRecord("staff", "manage", "Manage staff", "pharmacy"));

    @Override
    public List<PermissionRecord> listByDomain(String domain) {
      return all.stream().filter(p -> p.domain().equals(domain)).toList();
    }

    @Override
    public List<PermissionRecord> listByDomainAndResource(String domain, String resource) {
      return all.stream()
          .filter(p -> p.domain().equals(domain) && p.resource().equals(resource))
          .toList();
    }

    @Override
    public Optional<PermissionRecord> find(String domain, String resource, String action) {
      return all.stream()
          .filter(
              p ->
                  p.domain().equals(domain)
                      && p.resource().equals(resource)
                      && p.action().equals(action))
          .findFirst();
    }
  }
}

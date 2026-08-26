package com.nammamedmate.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.auth.adapter.in.web.dto.CreatePharmacyRoleRequest;
import com.nammamedmate.auth.adapter.in.web.dto.UpdateRolePermissionsRequest;
import com.nammamedmate.auth.application.AdminRolesService;
import com.nammamedmate.auth.application.PharmacyRolesService;
import com.nammamedmate.auth.application.RbacPermissionService;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

@ExtendWith(MockitoExtension.class)
class RbacControllersTest {

  @Mock private AdminRolesService adminRolesService;
  @Mock private PharmacyRolesService pharmacyRolesService;
  @Mock private RbacPermissionService rbacPermissionService;

  private AdminRolesController adminRolesController;
  private PharmacyRolesController pharmacyRolesController;
  private RequiresPermissionInterceptor interceptor;

  private final MedmatePrincipal admin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    adminRolesController = new AdminRolesController(adminRolesService);
    pharmacyRolesController = new PharmacyRolesController(pharmacyRolesService);
    interceptor = new RequiresPermissionInterceptor(rbacPermissionService);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void adminControllersDelegate() {
    when(adminRolesService.listRoles(admin)).thenReturn(List.of(Map.of("role", "admin_super")));
    when(adminRolesService.listPermissions(admin, "orders"))
        .thenReturn(List.of(Map.of("permission", "orders:read")));
    when(adminRolesService.getRolePermissions(admin, "admin_finance"))
        .thenReturn(Map.of("role", "admin_finance", "permission_count", 14));
    assertThat(adminRolesController.listRoles(admin).data()).hasSize(1);
    assertThat(adminRolesController.listPermissions(admin, "orders").data()).hasSize(1);
    assertThat(adminRolesController.getRolePermissions(admin, "admin_finance").data())
        .containsEntry("permission_count", 14);
  }

  @Test
  void pharmacyControllersDelegate() {
    when(pharmacyRolesService.listRoles(owner)).thenReturn(List.of(Map.of("name", "owner")));
    when(pharmacyRolesService.createRole(eq(owner), eq("x"), eq("X"), any()))
        .thenReturn(Map.of("id", UUID.randomUUID()));
    when(pharmacyRolesService.getPermissions(owner, "system-owner"))
        .thenReturn(Map.of("role_name", "owner"));
    when(pharmacyRolesService.updatePermissions(eq(owner), eq("id"), any()))
        .thenReturn(Map.of("role_id", "id"));

    assertThat(pharmacyRolesController.list(owner).data()).hasSize(1);
    assertThat(
            pharmacyRolesController
                .create(owner, new CreatePharmacyRoleRequest("x", "X", List.of("orders:read")))
                .data())
        .containsKey("id");
    assertThat(pharmacyRolesController.getPermissions(owner, "system-owner").data())
        .containsEntry("role_name", "owner");
    assertThat(
            pharmacyRolesController
                .updatePermissions(
                    owner, "id", new UpdateRolePermissionsRequest(List.of("orders:read")))
                .data())
        .containsKey("role_id");
    pharmacyRolesController.delete(owner, "id");
    verify(pharmacyRolesService).deleteRole(owner, "id");
  }

  @Test
  void interceptorEnforcesPermission() throws Exception {
    HandlerMethod method =
        new HandlerMethod(new SuspendProbe(), SuspendProbe.class.getMethod("go"));
    MedmatePrincipal support =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(support, null, List.of()));
    org.mockito.Mockito.doThrow(
            new AppException(
                "INSUFFICIENT_PERMISSIONS",
                "no",
                403,
                null,
                Map.of("required_permission", "pharmacies:suspend")))
        .when(rbacPermissionService)
        .requirePermission(support, "pharmacies:suspend");
    assertThatThrownBy(
            () ->
                interceptor.preHandle(
                    new MockHttpServletRequest(), new MockHttpServletResponse(), method))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INSUFFICIENT_PERMISSIONS");
  }

  @Test
  void interceptorAllowsWhenAnnotatedAndPermitted() throws Exception {
    HandlerMethod method =
        new HandlerMethod(new SuspendProbe(), SuspendProbe.class.getMethod("go"));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, List.of()));
    assertThat(
            interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), method))
        .isTrue();
    verify(rbacPermissionService).requirePermission(admin, "pharmacies:suspend");
  }

  @Test
  void interceptorSkipsNonHandlerMethods() {
    assertThat(
            interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
        .isTrue();
  }

  @Test
  void interceptorClassLevelAnnotationAndNullAuth() throws Exception {
    HandlerMethod method =
        new HandlerMethod(new ClassLevelProbe(), ClassLevelProbe.class.getMethod("go"));
    SecurityContextHolder.clearContext();
    assertThatThrownBy(
            () ->
                interceptor.preHandle(
                    new MockHttpServletRequest(), new MockHttpServletResponse(), method))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");

    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("anon", null, List.of()));
    assertThatThrownBy(
            () ->
                interceptor.preHandle(
                    new MockHttpServletRequest(), new MockHttpServletResponse(), method))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");

    assertThat(
            interceptor.preHandle(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new HandlerMethod(new UnannotatedProbe(), UnannotatedProbe.class.getMethod("go"))))
        .isTrue();
  }

  static final class SuspendProbe {
    @RequiresPermission("pharmacies:suspend")
    public void go() {}
  }

  @RequiresPermission("pharmacies:suspend")
  static final class ClassLevelProbe {
    public void go() {}
  }

  static final class UnannotatedProbe {
    public void go() {}
  }
}

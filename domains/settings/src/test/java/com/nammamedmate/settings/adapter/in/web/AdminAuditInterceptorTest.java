package com.nammamedmate.settings.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.settings.application.AuditLogService;
import com.nammamedmate.settings.application.port.out.AdminStaffStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminAuditInterceptorTest {

  private AuditLogService service;
  private AdminAuditInterceptor interceptor;

  @BeforeEach
  void setUp() {
    service = mock(AuditLogService.class);
    AdminStaffStore staff = mock(AdminStaffStore.class);
    when(staff.findById(any())).thenReturn(java.util.Optional.empty());
    Executor sync = Runnable::run;
    interceptor = new AdminAuditInterceptor(service, staff, sync);
    SecurityContextHolder.clearContext();
  }

  @Test
  void skipsSafeMethodsAndAuditLogPath() {
    MockHttpServletResponse res = new MockHttpServletResponse();
    for (String method : new String[] {"GET", "HEAD", "OPTIONS"}) {
      MockHttpServletRequest req = new MockHttpServletRequest(method, "/api/v1/admin/staff");
      interceptor.afterCompletion(req, res, new Object(), null);
    }
    interceptor.afterCompletion(
        new MockHttpServletRequest("POST", "/api/v1/admin/audit-log/x"), res, new Object(), null);
    verify(service, never())
        .appendMiddleware(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void logsMutatingAdminRequestWithStaffDisplayName() {
    UUID staffId = Ids.newId();
    AdminStaffStore named = mock(AdminStaffStore.class);
    when(named.findById(staffId))
        .thenReturn(
            java.util.Optional.of(
                new AdminStaffStore.AdminStaffRow(
                    staffId,
                    "Priya Ops",
                    "priya@test.in",
                    "admin_operations",
                    "ACTIVE",
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)));
    AdminAuditInterceptor namedInterceptor =
        new AdminAuditInterceptor(service, named, Runnable::run);
    MedmatePrincipal principal =
        new MedmatePrincipal(staffId, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null));

    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/admin/staff");
    MockHttpServletResponse res = new MockHttpServletResponse();
    assertThat(namedInterceptor.preHandle(req, res, new Object())).isTrue();
    namedInterceptor.afterCompletion(req, res, new Object(), null);

    verify(service)
        .appendMiddleware(
            eq(staffId),
            eq("Priya Ops"),
            eq("admin_operations"),
            eq("staff.create"),
            eq("staff"),
            isNull(),
            any(),
            any(),
            isNull());

    when(named.findById(staffId))
        .thenReturn(
            java.util.Optional.of(
                new AdminStaffStore.AdminStaffRow(
                    staffId,
                    "  ",
                    "blank@test.in",
                    "admin_operations",
                    "ACTIVE",
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)));
    namedInterceptor.afterCompletion(req, res, new Object(), null);
    verify(service)
        .appendMiddleware(
            eq(staffId),
            eq("admin_operations"),
            eq("admin_operations"),
            eq("staff.create"),
            eq("staff"),
            isNull(),
            any(),
            any(),
            isNull());

    when(named.findById(staffId))
        .thenReturn(
            java.util.Optional.of(
                new AdminStaffStore.AdminStaffRow(
                    staffId,
                    null,
                    "nullname@test.in",
                    "admin_operations",
                    "ACTIVE",
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)));
    namedInterceptor.afterCompletion(req, res, new Object(), null);
    verify(service, org.mockito.Mockito.atLeastOnce())
        .appendMiddleware(
            eq(staffId),
            eq("admin_operations"),
            eq("admin_operations"),
            eq("staff.create"),
            eq("staff"),
            isNull(),
            any(),
            any(),
            isNull());
  }

  @Test
  void logsMutatingAdminRequest() {
    UUID staffId = Ids.newId();
    MedmatePrincipal principal =
        new MedmatePrincipal(staffId, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null));

    MockHttpServletRequest req =
        new MockHttpServletRequest("PATCH", "/api/v1/admin/pharmacies/" + Ids.newId() + "/suspend");
    req.addHeader("X-Forwarded-For", "10.0.0.1, 10.0.0.2");
    req.addHeader("User-Agent", "JUnit");
    req.setQueryString("dry=1");
    MockHttpServletResponse res = new MockHttpServletResponse();
    res.setStatus(200);

    assertThat(interceptor.preHandle(req, res, new Object())).isTrue();
    interceptor.afterCompletion(req, res, new Object(), null);

    verify(service)
        .appendMiddleware(
            eq(staffId),
            eq("admin_operations"),
            eq("admin_operations"),
            eq("pharmacies.suspend"),
            eq("pharmacy"),
            any(UUID.class),
            any(),
            eq("10.0.0.1"),
            eq("JUnit"));
  }

  @Test
  void inferHelpersUnauthenticatedAndEdgePaths() {
    UUID id = Ids.newId();
    assertThat(AdminAuditInterceptor.inferAction("POST", "/api/v1/admin/staff"))
        .isEqualTo("staff.create");
    assertThat(AdminAuditInterceptor.inferAction("DELETE", "/api/v1/admin/staff/" + id))
        .isEqualTo("staff.delete");
    assertThat(AdminAuditInterceptor.inferAction("PUT", "/api/v1/admin/zones"))
        .isEqualTo("zones.update");
    assertThat(AdminAuditInterceptor.inferAction("PATCH", "/api/v1/admin/staff/" + id))
        .isEqualTo("staff.update");
    assertThat(AdminAuditInterceptor.inferAction("PATCH", "/api/v1/admin/staff"))
        .isEqualTo("staff.update");
    assertThat(AdminAuditInterceptor.inferAction("PATCH", "/api/v1/admin/staff/extra/" + id))
        .isEqualTo("staff.update");
    assertThat(
            AdminAuditInterceptor.inferAction(
                "PATCH", "/api/v1/admin/pharmacies/" + id + "/suspend"))
        .isEqualTo("pharmacies.suspend");
    assertThat(AdminAuditInterceptor.inferAction("TRACE", "/api/v1/admin/staff"))
        .isEqualTo("staff.trace");
    assertThat(AdminAuditInterceptor.inferAction("POST", "staff")).isEqualTo("staff.create");
    assertThat(AdminAuditInterceptor.inferAction("OPTIONS", "/api/v1/admin/")).isEqualTo("options");
    assertThat(AdminAuditInterceptor.inferResourceType("/api/v1/admin/feature-flags"))
        .isEqualTo("feature_flag");
    assertThat(AdminAuditInterceptor.inferResourceType("/api/v1/admin/")).isEqualTo("admin");
    assertThat(AdminAuditInterceptor.inferResourceType("/api/v1/admin/config")).isEqualTo("config");
    assertThat(AdminAuditInterceptor.inferResourceType("pharmacies")).isEqualTo("pharmacy");
    assertThat(AdminAuditInterceptor.inferResourceId("/api/v1/admin/staff")).isNull();
    assertThat(AdminAuditInterceptor.inferResourceId("/api/v1/admin/staff/" + id)).isEqualTo(id);
    assertThat(AdminAuditInterceptor.inferResourceId(id.toString())).isEqualTo(id);
    assertThat(AdminAuditInterceptor.inferResourceId("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz"))
        .isNull();
    assertThat(AdminAuditInterceptor.inferResourceId("/x/not-a-uuid")).isNull();
    assertThat(AdminAuditInterceptor.inferResourceId("/x/12345678-1234-1234-1234-12345678901"))
        .isNull(); // length 35
    assertThat(AdminAuditInterceptor.inferResourceId(null)).isNull();
    assertThat(AdminAuditInterceptor.inferResourceId("")).isNull();
    assertThat(AdminAuditInterceptor.inferResourceId("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
        .isNull();
    assertThat(AdminAuditInterceptor.inferResourceId("12345678x1234-1234-1234-123456789012"))
        .isNull();
    assertThat(AdminAuditInterceptor.inferResourceId("12345678-1234x1234-1234-123456789012"))
        .isNull();
    assertThat(AdminAuditInterceptor.inferResourceId("12345678-1234-1234x1234-123456789012"))
        .isNull();
    assertThat(AdminAuditInterceptor.inferResourceId("12345678-1234-1234-1234x123456789012"))
        .isNull();
    assertThat(AdminAuditInterceptor.inferResourceType("/api/v1/admin/ies")).isEqualTo("ie");
    assertThat(AdminAuditInterceptor.inferResourceType("/api/v1/admin/s")).isEqualTo("s");

    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("not-principal", null));
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/admin/staff");
    req.addHeader("X-Forwarded-For", " ");
    req.setQueryString("");
    MockHttpServletResponse res = new MockHttpServletResponse();
    interceptor.preHandle(req, res, new Object());
    interceptor.afterCompletion(req, res, new Object(), null);

    // auth == null
    SecurityContextHolder.clearContext();
    interceptor.preHandle(
        new MockHttpServletRequest("POST", "/api/v1/admin/staff"), res, new Object());
    verify(service)
        .appendMiddleware(
            isNull(),
            eq("unknown"),
            eq("unknown"),
            eq("staff.create"),
            eq("staff"),
            isNull(),
            any(),
            anyString(),
            isNull());

    MockHttpServletRequest singleXff = new MockHttpServletRequest("POST", "/api/v1/admin/staff");
    singleXff.addHeader("X-Forwarded-For", "9.9.9.9");
    interceptor.afterCompletion(singleXff, res, new Object(), null);

    MockHttpServletRequest other = new MockHttpServletRequest("POST", "/api/v1/customer/x");
    interceptor.afterCompletion(other, res, new Object(), null);

    HttpServletRequest bare = mock(HttpServletRequest.class);
    org.mockito.Mockito.when(bare.getMethod()).thenReturn(null);
    interceptor.afterCompletion(bare, mock(HttpServletResponse.class), new Object(), null);

    HttpServletRequest nullPath = mock(HttpServletRequest.class);
    org.mockito.Mockito.when(nullPath.getMethod()).thenReturn("POST");
    org.mockito.Mockito.when(nullPath.getRequestURI()).thenReturn(null);
    interceptor.afterCompletion(nullPath, mock(HttpServletResponse.class), new Object(), null);

    HttpServletRequest xff = mock(HttpServletRequest.class);
    org.mockito.Mockito.when(xff.getMethod()).thenReturn("POST");
    org.mockito.Mockito.when(xff.getRequestURI()).thenReturn("/api/v1/admin/staff");
    org.mockito.Mockito.when(xff.getRemoteAddr()).thenReturn("127.0.0.1");
    org.mockito.Mockito.when(xff.getHeader("X-Forwarded-For")).thenReturn(null);
    interceptor.afterCompletion(xff, res, new Object(), null);
    org.mockito.Mockito.when(xff.getHeader("X-Forwarded-For")).thenReturn("");
    interceptor.afterCompletion(xff, res, new Object(), null);
    org.mockito.Mockito.when(xff.getHeader("X-Forwarded-For")).thenReturn("\t");
    interceptor.afterCompletion(xff, res, new Object(), null);
  }
}

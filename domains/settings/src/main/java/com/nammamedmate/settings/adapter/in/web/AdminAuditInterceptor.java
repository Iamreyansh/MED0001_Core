package com.nammamedmate.settings.adapter.in.web;

import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.settings.application.AuditLogService;
import com.nammamedmate.settings.application.port.out.AdminStaffStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Fire-and-forget metadata audit for mutating admin APIs. Explicit service appends continue to
 * write rich before/after snapshots.
 */
@Component
public class AdminAuditInterceptor implements HandlerInterceptor {

  private static final String ATTR_PRINCIPAL = "medmate.audit.principal";
  private static final Pattern UUID_TAIL =
      Pattern.compile(
          ".*/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:/.*)?$");

  private final AuditLogService auditLogService;
  private final AdminStaffStore staff;
  private final Executor auditExecutor;

  public AdminAuditInterceptor(
      AuditLogService auditLogService,
      AdminStaffStore staff,
      @Qualifier("auditExecutor") Executor auditExecutor) {
    this.auditLogService = auditLogService;
    this.staff = staff;
    this.auditExecutor = auditExecutor;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof MedmatePrincipal principal) {
      request.setAttribute(ATTR_PRINCIPAL, principal);
    }
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    String method = request.getMethod();
    if (method == null) {
      return;
    }
    String upper = method.toUpperCase(Locale.ROOT);
    if ("GET".equals(upper) || "HEAD".equals(upper) || "OPTIONS".equals(upper)) {
      return;
    }
    String path = request.getRequestURI();
    if (path == null || !path.startsWith("/api/v1/admin/")) {
      return;
    }
    if (path.startsWith("/api/v1/admin/audit-log")) {
      return;
    }

    MedmatePrincipal principal = (MedmatePrincipal) request.getAttribute(ATTR_PRINCIPAL);
    String action = inferAction(upper, path);
    String resourceType = inferResourceType(path);
    UUID resourceId = inferResourceId(path);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("method", upper);
    metadata.put("url", path);
    metadata.put("status_code", response.getStatus());
    String query = request.getQueryString();
    if (query != null && !query.isBlank()) {
      metadata.put("query_params", query);
    }
    String ip = clientIp(request);
    String ua = request.getHeader("User-Agent");
    UUID actorId = principal == null ? null : principal.subject();
    String role = principal == null ? "unknown" : principal.role().value();
    final String actorName =
        principal == null
            ? "unknown"
            : staff
                .findById(principal.subject())
                .map(AdminStaffStore.AdminStaffRow::name)
                .filter(n -> !n.isBlank())
                .orElse(principal.role().value());

    auditExecutor.execute(
        () ->
            auditLogService.appendMiddleware(
                actorId, actorName, role, action, resourceType, resourceId, metadata, ip, ua));
  }

  static String inferAction(String method, String path) {
    String trimmed =
        path.startsWith("/api/v1/admin/") ? path.substring("/api/v1/admin/".length()) : path;
    String[] parts = trimmed.split("/");
    if (parts[0].isBlank()) {
      return method.toLowerCase(Locale.ROOT);
    }
    String resource = parts[0].replace('-', '_');
    String verb =
        switch (method.toUpperCase(Locale.ROOT)) {
          case "POST" -> "create";
          case "PUT" -> "update";
          case "PATCH" -> patchVerb(parts);
          case "DELETE" -> "delete";
          default -> method.toLowerCase(Locale.ROOT);
        };
    return resource + "." + verb;
  }

  private static String patchVerb(String[] parts) {
    if (parts.length < 3) {
      return "update";
    }
    String last = parts[parts.length - 1];
    if (looksLikeUuid(last)) {
      return "update";
    }
    return last.replace('-', '_');
  }

  static String inferResourceType(String path) {
    String trimmed =
        path.startsWith("/api/v1/admin/") ? path.substring("/api/v1/admin/".length()) : path;
    String[] parts = trimmed.split("/");
    if (parts[0].isBlank()) {
      return "admin";
    }
    String r = parts[0].replace('-', '_');
    if (r.endsWith("ies") && r.length() > 3) {
      return r.substring(0, r.length() - 3) + "y";
    }
    if (r.endsWith("s") && r.length() > 1) {
      return r.substring(0, r.length() - 1);
    }
    return r;
  }

  static UUID inferResourceId(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    Matcher m = UUID_TAIL.matcher(path);
    if (m.matches()) {
      return UUID.fromString(m.group(1));
    }
    for (String part : path.split("/")) {
      if (looksLikeUuid(part)) {
        try {
          return UUID.fromString(part);
        } catch (RuntimeException ignored) {
          // structurally UUID-shaped but invalid
        }
      }
    }
    return null;
  }

  private static boolean looksLikeUuid(String value) {
    if (value.length() != 36) {
      return false;
    }
    return value.charAt(8) == '-'
        && value.charAt(13) == '-'
        && value.charAt(18) == '-'
        && value.charAt(23) == '-';
  }

  private static String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded == null || forwarded.isBlank()) {
      return request.getRemoteAddr();
    }
    int comma = forwarded.indexOf(',');
    if (comma < 0) {
      return forwarded.trim();
    }
    return forwarded.substring(0, comma).trim();
  }
}

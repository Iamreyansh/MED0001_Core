package com.nammamedmate.crm.adapter.in.web;

import com.nammamedmate.crm.application.port.out.ModuleUsageMeterPort;
import com.nammamedmate.security.MedmatePrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * After-request best-effort monthly usage upsert for pharmacy ERP module paths. Never fails the
 * request.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class CrmUsageMeteringFilter extends OncePerRequestFilter {

  private final ModuleUsageMeterPort meter;

  public CrmUsageMeteringFilter(ModuleUsageMeterPort meter) {
    this.meter = meter;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      filterChain.doFilter(request, response);
    } finally {
      recordBestEffort(request);
    }
  }

  private void recordBestEffort(HttpServletRequest request) {
    try {
      String moduleId = CrmModulePaths.resolveModule(request.getRequestURI());
      if (moduleId == null) {
        return;
      }
      MedmatePrincipal principal = currentPrincipal();
      if (principal == null || principal.pharmacyId() == null) {
        return;
      }
      meter.recordUsage(principal.pharmacyId(), moduleId);
    } catch (RuntimeException ignored) {
      // metering must never surface to the client
    }
  }

  private static MedmatePrincipal currentPrincipal() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof MedmatePrincipal p)) {
      return null;
    }
    return p;
  }
}

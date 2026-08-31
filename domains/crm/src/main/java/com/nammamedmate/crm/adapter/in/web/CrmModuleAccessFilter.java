package com.nammamedmate.crm.adapter.in.web;

import com.nammamedmate.crm.application.port.out.CrmPlanLookupPort;
import com.nammamedmate.security.MedmatePrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Module-matrix enforcement. Disabled by default ({@code medmate.crm.enforce-module-matrix}).
 * Override beats plan matrix via {@link CrmPlanLookupPort#moduleAccessibleForPharmacy}.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class CrmModuleAccessFilter extends OncePerRequestFilter {

  private final CrmPlanLookupPort planLookup;
  private final boolean enforce;

  public CrmModuleAccessFilter(
      CrmPlanLookupPort planLookup,
      @Value("${medmate.crm.enforce-module-matrix:false}") boolean enforce) {
    this.planLookup = planLookup;
    this.enforce = enforce;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!enforce) {
      filterChain.doFilter(request, response);
      return;
    }
    String moduleId = CrmModulePaths.resolveModule(request.getRequestURI());
    if (moduleId == null) {
      filterChain.doFilter(request, response);
      return;
    }
    MedmatePrincipal principal = currentPrincipal();
    if (principal == null || principal.pharmacyId() == null) {
      filterChain.doFilter(request, response);
      return;
    }
    try {
      if (!planLookup.moduleAccessibleForPharmacy(principal.pharmacyId(), moduleId)) {
        writeModuleDenied(response, moduleId);
        return;
      }
    } catch (RuntimeException ex) {
      writeModuleDenied(response, moduleId);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private static void writeModuleDenied(HttpServletResponse response, String moduleId)
      throws IOException {
    response.setStatus(403);
    response.setCharacterEncoding("UTF-8");
    response.setContentType("application/json");
    response
        .getWriter()
        .write(
            "{\"success\":false,\"error\":{\"code\":\"MODULE_NOT_IN_PLAN\",\"message\":\"Module "
                + moduleId
                + " is not accessible for this account\"}}");
  }

  static String resolveModule(String uri) {
    return CrmModulePaths.resolveModule(uri);
  }

  private static MedmatePrincipal currentPrincipal() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof MedmatePrincipal p)) {
      return null;
    }
    return p;
  }
}

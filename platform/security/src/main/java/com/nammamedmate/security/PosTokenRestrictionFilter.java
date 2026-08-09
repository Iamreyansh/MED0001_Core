package com.nammamedmate.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Blocks POS-scoped tokens from non-POS API paths. */
public final class PosTokenRestrictionFilter extends OncePerRequestFilter {

  private static final String ERROR_BODY =
      "{\"success\":false,\"error\":{\"code\":\"POS_TOKEN_RESTRICTED\",\"message\":\"POS token cannot access this endpoint\"}}";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null
        && auth.getPrincipal() instanceof MedmatePrincipal principal
        && principal.tokenScope() == TokenScope.POS
        && !isAllowedForPos(request.getRequestURI())) {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter().write(ERROR_BODY);
      return;
    }
    filterChain.doFilter(request, response);
  }

  static boolean isAllowedForPos(String uri) {
    if (uri == null) {
      return false;
    }
    // BR6: POS tokens only for POS APIs + health probes. Public routes need no POS exception.
    return uri.startsWith("/api/v1/pos/")
        || uri.startsWith("/api/v1/pharmacy/pos/")
        || uri.equals("/api/v1/health")
        || uri.equals("/actuator/health");
  }
}

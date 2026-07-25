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

/** Blocks MFA-challenge tokens from non-verify-mfa API paths. */
public final class MfaChallengeRestrictionFilter extends OncePerRequestFilter {

  private static final String ERROR_BODY =
      "{\"success\":false,\"error\":{\"code\":\"CHALLENGE_TOKEN_INVALID\",\"message\":\"MFA challenge token cannot access this endpoint\"}}";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null
        && auth.getPrincipal() instanceof MedmatePrincipal principal
        && principal.tokenScope() == TokenScope.MFA_CHALLENGE
        && !isAllowedForMfaChallenge(request.getRequestURI())) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter().write(ERROR_BODY);
      return;
    }
    filterChain.doFilter(request, response);
  }

  static boolean isAllowedForMfaChallenge(String uri) {
    if (uri == null) {
      return false;
    }
    return uri.equals("/api/v1/auth/admin/verify-mfa")
        || uri.equals("/api/v1/health")
        || uri.equals("/actuator/health");
  }
}

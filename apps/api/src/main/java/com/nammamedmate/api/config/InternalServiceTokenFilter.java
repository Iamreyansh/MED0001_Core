package com.nammamedmate.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/** Edge gate for token-only internal routes. JWT accounting paths are skipped. */
final class InternalServiceTokenFilter extends OncePerRequestFilter {

  private final String expectedToken;

  InternalServiceTokenFilter(String expectedToken) {
    this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String path = request.getRequestURI() == null ? "" : request.getRequestURI();
    if (!requiresToken(path)) {
      filterChain.doFilter(request, response);
      return;
    }
    if (expectedToken.isEmpty()) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Internal token is not configured");
      return;
    }
    String provided = request.getHeader("X-Internal-Token");
    String token = provided == null ? "" : provided.trim();
    if (token.isEmpty()
        || !MessageDigest.isEqual(
            expectedToken.getBytes(StandardCharsets.UTF_8),
            token.getBytes(StandardCharsets.UTF_8))) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing X-Internal-Token");
      return;
    }
    filterChain.doFilter(request, response);
  }

  static boolean requiresToken(String path) {
    if (path == null || path.isBlank()) {
      return false;
    }
    if (path.startsWith("/api/v1/integrations/accounting")) {
      return false;
    }
    return path.startsWith("/api/v1/wallet/")
        || path.startsWith("/api/v1/internal/")
        || path.startsWith("/api/v1/integrations/");
  }
}

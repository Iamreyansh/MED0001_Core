package com.nammamedmate.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Captures Idempotency-Key for mutating requests. Persistence/dedupe lands with payment stories.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

  public static final String HEADER = "Idempotency-Key";
  public static final String ATTR = "com.nammamedmate.idempotencyKey";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String key = request.getHeader(HEADER);
    if (key != null && !key.isBlank()) {
      request.setAttribute(ATTR, key.trim());
    }
    filterChain.doFilter(request, response);
  }
}

package com.nammamedmate.kernel.webhook;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Ensures webhook routes expose a rereadable body for HMAC verification (e.g. Razorpay). */
public final class WebhookRawBodyFilter extends OncePerRequestFilter {

  public static final String CACHED_BODY_ATTR = "com.nammamedmate.webhook.rawBody";

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    if (path == null) {
      return true;
    }
    return !path.startsWith("/api/v1/webhooks") && !path.startsWith("/api/v1/internal/kyc/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    byte[] body = request.getInputStream().readAllBytes();
    CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(request, body);
    wrapped.setAttribute(CACHED_BODY_ATTR, body);
    filterChain.doFilter(wrapped, response);
  }

  public static byte[] rawBody(HttpServletRequest request) {
    Object attr = request.getAttribute(CACHED_BODY_ATTR);
    if (attr instanceof byte[] bytes) {
      return bytes.clone();
    }
    return new byte[0];
  }
}

package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

class InternalServiceTokenFilterTest {

  @Test
  void skipsNonInternalAndAccounting() throws Exception {
    InternalServiceTokenFilter filter = new InternalServiceTokenFilter("secret");
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getRequestURI()).thenReturn("/api/v1/orders/1");
    filter.doFilter(req, res, chain);
    verify(chain).doFilter(req, res);

    when(req.getRequestURI()).thenReturn("/api/v1/integrations/accounting/sync-status/1");
    filter.doFilter(req, res, chain);
    verify(chain, org.mockito.Mockito.times(2)).doFilter(req, res);
    assertThat(InternalServiceTokenFilter.requiresToken("/api/v1/wallet/credit")).isTrue();
    assertThat(InternalServiceTokenFilter.requiresToken("/api/v1/internal/kyc/x")).isTrue();
    assertThat(InternalServiceTokenFilter.requiresToken(null)).isFalse();
    assertThat(InternalServiceTokenFilter.requiresToken("")).isFalse();
    assertThat(InternalServiceTokenFilter.requiresToken("  ")).isFalse();

    InternalServiceTokenFilter nullToken = new InternalServiceTokenFilter(null);
    when(req.getRequestURI()).thenReturn(null);
    nullToken.doFilter(req, res, chain);
    verify(chain, org.mockito.Mockito.times(3)).doFilter(req, res);
  }

  @Test
  void rejectsMissingOrWrongToken() throws Exception {
    InternalServiceTokenFilter filter = new InternalServiceTokenFilter("secret");
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getRequestURI()).thenReturn("/api/v1/wallet/credit");
    when(req.getHeader("X-Internal-Token")).thenReturn(null);
    filter.doFilter(req, res, chain);
    verify(res).sendError(401, "Invalid or missing X-Internal-Token");
    verify(chain, never()).doFilter(req, res);

    when(req.getHeader("X-Internal-Token")).thenReturn("nope");
    filter.doFilter(req, res, chain);
    verify(res, org.mockito.Mockito.times(2)).sendError(401, "Invalid or missing X-Internal-Token");
  }

  @Test
  void rejectsWhenTokenNotConfigured() throws Exception {
    InternalServiceTokenFilter filter = new InternalServiceTokenFilter(" ");
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getRequestURI()).thenReturn("/api/v1/integrations/maps/geocode");
    filter.doFilter(req, res, chain);
    verify(res).sendError(401, "Internal token is not configured");
  }

  @Test
  void acceptsMatchingToken() throws Exception {
    InternalServiceTokenFilter filter = new InternalServiceTokenFilter("secret");
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getRequestURI()).thenReturn("/api/v1/wallet/credit");
    when(req.getHeader("X-Internal-Token")).thenReturn("secret");
    filter.doFilter(req, res, chain);
    verify(chain).doFilter(req, res);
  }
}

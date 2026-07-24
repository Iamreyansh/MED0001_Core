package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class IdempotencyFilterTest {

  @Test
  void storesHeaderWhenPresent() throws Exception {
    IdempotencyFilter filter = new IdempotencyFilter();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(IdempotencyFilter.HEADER, " abc ");
    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    assertThat(request.getAttribute(IdempotencyFilter.ATTR)).isEqualTo("abc");
  }

  @Test
  void ignoresMissingHeader() throws Exception {
    IdempotencyFilter filter = new IdempotencyFilter();
    MockHttpServletRequest request = new MockHttpServletRequest();
    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    assertThat(request.getAttribute(IdempotencyFilter.ATTR)).isNull();
  }

  @Test
  void ignoresBlankHeader() throws Exception {
    IdempotencyFilter filter = new IdempotencyFilter();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(IdempotencyFilter.HEADER, "   ");
    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    assertThat(request.getAttribute(IdempotencyFilter.ATTR)).isNull();
  }
}

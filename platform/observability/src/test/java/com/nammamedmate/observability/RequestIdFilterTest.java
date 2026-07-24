package com.nammamedmate.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

  @Test
  void generatesAndPropagatesRequestId() throws Exception {
    RequestIdFilter filter = new RequestIdFilter();
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(
        request, response, (req, res) -> assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNotBlank());
    assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotBlank();
    assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
  }

  @Test
  void reusesIncomingHeader() throws Exception {
    RequestIdFilter filter = new RequestIdFilter();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(RequestIdFilter.HEADER, "fixed-id");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("fixed-id");
  }

  @Test
  void regeneratesWhenHeaderBlank() throws Exception {
    RequestIdFilter filter = new RequestIdFilter();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(RequestIdFilter.HEADER, "  ");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotBlank();
    assertThat(response.getHeader(RequestIdFilter.HEADER).trim()).isNotEmpty();
  }
}

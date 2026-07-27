package com.nammamedmate.kernel.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WebhookRawBodyFilterTest {

  @Test
  void cachesBodyForWebhookPaths() throws Exception {
    WebhookRawBodyFilter filter = new WebhookRawBodyFilter();
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/webhooks/razorpay");
    byte[] payload = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
    request.setContent(payload);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain =
        (req, res) -> {
          CachedBodyHttpServletRequest cached = (CachedBodyHttpServletRequest) req;
          assertThat(cached.getCachedBody()).isEqualTo(payload);
          assertThat(cached.getInputStream().readAllBytes()).isEqualTo(payload);
          assertThat(cached.getReader().readLine()).isEqualTo("{\"ok\":true}");
          assertThat(WebhookRawBodyFilter.rawBody(cached)).isEqualTo(payload);
        };
    filter.doFilter(request, response, chain);
  }

  @Test
  void cachesBodyForInternalKycPaths() throws Exception {
    WebhookRawBodyFilter filter = new WebhookRawBodyFilter();
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/internal/kyc/webhook-callback");
    byte[] payload = "{\"provider\":\"FSSAI_PORTAL_API\"}".getBytes(StandardCharsets.UTF_8);
    request.setContent(payload);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain =
        (req, res) -> {
          CachedBodyHttpServletRequest cached = (CachedBodyHttpServletRequest) req;
          assertThat(WebhookRawBodyFilter.rawBody(cached)).isEqualTo(payload);
        };
    filter.doFilter(request, response, chain);
  }

  @Test
  void skipsNonWebhookPaths() throws Exception {
    WebhookRawBodyFilter filter = new WebhookRawBodyFilter();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/api/v1/health");
    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    filter.doFilter(request, response, chain);
    verify(chain).doFilter(request, response);

    HttpServletRequest nullPath = mock(HttpServletRequest.class);
    when(nullPath.getRequestURI()).thenReturn(null);
    FilterChain chain2 = mock(FilterChain.class);
    filter.doFilter(nullPath, response, chain2);
    verify(chain2).doFilter(nullPath, response);
  }

  @Test
  void rawBodyEmptyWhenMissingAttr() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    assertThat(WebhookRawBodyFilter.rawBody(request)).isEmpty();
  }

  @Test
  void cachedRequestHandlesNullBodyAndStreamFlags() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request, null);
    assertThat(cached.getCachedBody()).isEmpty();
    ServletInputStream stream = cached.getInputStream();
    assertThat(stream.isReady()).isTrue();
    assertThat(stream.isFinished()).isTrue();
    stream.setReadListener(null);
    CachedBodyHttpServletRequest withData =
        new CachedBodyHttpServletRequest(request, "ab".getBytes(StandardCharsets.UTF_8));
    ServletInputStream open = withData.getInputStream();
    assertThat(open.isFinished()).isFalse();
    assertThat(open.read()).isEqualTo('a');
    assertThat(new ByteArrayInputStream(new byte[0]).available()).isZero();
  }
}

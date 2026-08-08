package com.nammamedmate.settings.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.settings.application.FeatureFlagService;
import com.nammamedmate.settings.application.FeatureFlagService.CheckResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class FeatureFlagCheckControllerTest {

  private FeatureFlagService service;
  private FeatureFlagCheckController controller;

  @BeforeEach
  void setUp() {
    service = mock(FeatureFlagService.class);
    controller = new FeatureFlagCheckController(service);
  }

  @Test
  void ac3_setsCacheControlAndEnvelope() {
    Instant at = Instant.parse("2026-07-24T02:00:00Z");
    when(service.check(eq("cod_enabled,new_checkout_flow"), isNull(), eq("127.0.0.1")))
        .thenReturn(new CheckResult(Map.of("cod_enabled", true, "new_checkout_flow", true), at));

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRemoteAddr()).thenReturn("127.0.0.1");

    ResponseEntity<Map<String, Object>> res =
        controller.check("cod_enabled,new_checkout_flow", null, req);

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertThat(res.getHeaders().getCacheControl()).contains("max-age=60").contains("public");
    assertThat(res.getBody()).containsEntry("success", true);
    assertThat(res.getBody().get("data")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> meta = (Map<String, Object>) res.getBody().get("meta");
    assertThat(meta)
        .containsEntry("cache_max_age", 60)
        .containsEntry("evaluated_at", at.toString());
  }

  @Test
  void nullRequestAndBlankIp() {
    when(service.check(eq("x"), eq("staging"), eq("0.0.0.0")))
        .thenReturn(new CheckResult(Map.of("x", false), Instant.now()));
    assertThat(
            controller.check("x", "staging", null).getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
        .isNotBlank();

    HttpServletRequest blank = mock(HttpServletRequest.class);
    when(blank.getRemoteAddr()).thenReturn("  ");
    when(service.check(eq("x"), isNull(), eq("0.0.0.0")))
        .thenReturn(new CheckResult(Map.of("x", true), Instant.now()));
    assertThat(controller.check("x", null, blank).getBody()).containsEntry("success", true);

    HttpServletRequest nullIp = mock(HttpServletRequest.class);
    when(nullIp.getRemoteAddr()).thenReturn(null);
    when(service.check(eq("y"), isNull(), eq("0.0.0.0")))
        .thenReturn(new CheckResult(Map.of("y", false), Instant.now()));
    assertThat(controller.check("y", null, nullIp).getBody()).containsEntry("success", true);
  }
}

package com.nammamedmate.settings.adapter.in.web;

import com.nammamedmate.settings.application.FeatureFlagService;
import com.nammamedmate.settings.application.FeatureFlagService.CheckResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/feature-flags")
@Tag(name = "Feature flags (public)")
public class FeatureFlagCheckController {

  private final FeatureFlagService service;

  public FeatureFlagCheckController(FeatureFlagService service) {
    this.service = service;
  }

  @GetMapping("/check")
  @Operation(summary = "Public base flag check for frontend SDK")
  public ResponseEntity<Map<String, Object>> check(
      @RequestParam(required = false) String flags,
      @RequestParam(required = false) String environment,
      HttpServletRequest request) {
    CheckResult result = service.check(flags, environment, clientIp(request));
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("evaluated_at", result.evaluatedAt().toString());
    meta.put("cache_max_age", 60);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", result.flags());
    body.put("meta", meta);

    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(60)).cachePublic())
        .body(body);
  }

  private static String clientIp(HttpServletRequest request) {
    if (request == null) {
      return "0.0.0.0";
    }
    String remote = request.getRemoteAddr();
    return remote == null || remote.isBlank() ? "0.0.0.0" : remote.trim();
  }
}

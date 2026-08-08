package com.nammamedmate.settings.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.settings.application.FeatureFlagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/feature-flags")
@Tag(name = "Admin feature flags")
public class AdminFeatureFlagController {

  private final FeatureFlagService service;

  public AdminFeatureFlagController(FeatureFlagService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List feature flags for an environment")
  public ApiResponse<List<Map<String, Object>>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String environment) {
    return ApiResponse.ok(service.list(principal, environment));
  }

  @GetMapping("/summary")
  @Operation(summary = "Feature flag summary counts")
  public ApiResponse<Map<String, Object>> summary(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.summary(principal));
  }

  @PatchMapping("/{name}")
  @Operation(summary = "Update feature flag kill-switch / rollout")
  public ApiResponse<Map<String, Object>> update(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("name") String name,
      @RequestParam(required = false) String environment,
      @RequestBody(required = false) UpdateRequest body) {
    UpdateRequest req = body == null ? new UpdateRequest(null, null, null) : body;
    return ApiResponse.ok(
        service.update(
            principal, name, environment, req.enabled(), req.rolloutPercentage(), req.notes()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UpdateRequest(Boolean enabled, Integer rolloutPercentage, String notes) {}
}

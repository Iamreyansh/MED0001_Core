package com.nammamedmate.crm.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.crm.application.FeatureAdoptionService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/crm")
@Tag(name = "Admin CRM feature adoption")
public class AdminCrmModuleController {

  private final FeatureAdoptionService adoption;

  public AdminCrmModuleController(FeatureAdoptionService adoption) {
    this.adoption = adoption;
  }

  @GetMapping("/modules")
  @RequiresPermission("crm:read")
  @Operation(summary = "Admin: feature adoption overview")
  public ApiResponse<Map<String, Object>> listModules(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String group,
      @RequestParam(required = false) String tier,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String order) {
    return ApiResponse.ok(adoption.listModules(principal, group, tier, sort, order));
  }

  @GetMapping("/modules/{moduleId}")
  @RequiresPermission("crm:write")
  @Operation(summary = "Admin: module adoption detail")
  public ApiResponse<Map<String, Object>> getModule(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable String moduleId) {
    return ApiResponse.ok(adoption.getModule(principal, moduleId));
  }

  @PostMapping("/accounts/{accountId}/modules/{moduleId}/toggle")
  @RequiresPermission("crm:write")
  @Operation(summary = "Admin: enable/disable module for account (override)")
  public ApiResponse<Map<String, Object>> toggle(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID accountId,
      @PathVariable String moduleId,
      @RequestBody ToggleRequest body) {
    return ApiResponse.ok(
        adoption.toggleModule(
            principal,
            accountId,
            moduleId,
            body == null ? null : body.enabled(),
            body == null ? null : body.reason()));
  }

  @GetMapping("/accounts/{accountId}/usage-summary")
  @RequiresPermission("crm:write")
  @Operation(summary = "Admin: account usage / adoption summary")
  public ApiResponse<Map<String, Object>> usageSummary(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID accountId) {
    return ApiResponse.ok(adoption.usageSummary(principal, accountId));
  }

  @PostMapping("/modules/nudge-ineligible")
  @RequiresPermission("crm:write")
  @Operation(summary = "Admin: nudge eligible-but-not-using accounts")
  public ApiResponse<Map<String, Object>> nudge(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody NudgeRequest body) {
    return ApiResponse.ok(
        adoption.nudgeIneligible(
            principal,
            body == null ? null : body.moduleId(),
            body == null ? null : body.channel()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ToggleRequest(Boolean enabled, String reason) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record NudgeRequest(String moduleId, String channel) {}
}

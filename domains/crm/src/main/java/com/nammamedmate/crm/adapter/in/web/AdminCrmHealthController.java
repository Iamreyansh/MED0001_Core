package com.nammamedmate.crm.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.crm.application.AccountHealthService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@Tag(name = "Admin CRM account health")
public class AdminCrmHealthController {

  private final AccountHealthService health;

  public AdminCrmHealthController(AccountHealthService health) {
    this.health = health;
  }

  @GetMapping("/accounts/{accountId}/health")
  @RequiresPermission("crm:read")
  @Operation(summary = "Admin: get account health score")
  public ApiResponse<Map<String, Object>> getHealth(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID accountId) {
    return ApiResponse.ok(health.getHealth(principal, accountId));
  }

  @GetMapping("/at-risk")
  @RequiresPermission("crm:read")
  @Operation(summary = "Admin: list at-risk / churning accounts")
  public ApiResponse<Map<String, Object>> listAtRisk(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "health_band", required = false) String healthBand,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    AccountHealthService.PagedResult result = health.listAtRisk(principal, healthBand, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/accounts/{accountId}/health/save-play")
  @RequiresPermission("crm:write")
  @Operation(summary = "Admin: log CSM save play action")
  public ResponseEntity<ApiResponse<Map<String, Object>>> savePlay(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID accountId,
      @RequestBody(required = false) SavePlayRequest body) {
    Map<String, Object> data =
        health.logSavePlay(
            principal,
            accountId,
            body == null ? null : body.actionType(),
            body == null ? null : body.outcome(),
            body == null ? null : body.notes());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @GetMapping("/accounts/{accountId}/usage")
  @RequiresPermission("crm:write")
  @Operation(summary = "Admin: account module usage chart (last 30 days)")
  public ApiResponse<Map<String, Object>> getUsage(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID accountId) {
    return ApiResponse.ok(health.getUsage(principal, accountId));
  }

  @GetMapping("/health-kpis")
  @RequiresPermission("crm:read")
  @Operation(summary = "Admin: platform health KPIs")
  public ApiResponse<Map<String, Object>> healthKpis(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(health.healthKpis(principal));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SavePlayRequest(String actionType, String outcome, String notes) {}
}

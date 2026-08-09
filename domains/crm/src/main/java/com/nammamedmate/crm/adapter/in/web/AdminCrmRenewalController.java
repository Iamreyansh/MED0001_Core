package com.nammamedmate.crm.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.crm.application.RenewalChurnService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/crm")
@Tag(name = "Admin CRM renewals & churn")
public class AdminCrmRenewalController {

  private final RenewalChurnService renewalChurn;

  public AdminCrmRenewalController(RenewalChurnService renewalChurn) {
    this.renewalChurn = renewalChurn;
  }

  @GetMapping("/renewals")
  @RequiresPermission("crm:read")
  @Operation(summary = "Admin: renewal pipeline dashboard")
  public ApiResponse<Map<String, Object>> dashboard(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(renewalChurn.dashboard(principal));
  }

  @GetMapping("/renewals/upcoming")
  @RequiresPermission("crm:read")
  @Operation(summary = "Admin: list upcoming renewals")
  public ApiResponse<Map<String, Object>> upcoming(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) Integer days,
      @RequestParam(name = "risk_level", required = false) String riskLevel,
      @RequestParam(name = "csm_id", required = false) UUID csmId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    RenewalChurnService.PagedResult result =
        renewalChurn.listUpcoming(principal, days, riskLevel, csmId, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/accounts/{accountId}/renew")
  @RequiresPermission("crm:write")
  @Operation(summary = "Admin: trigger manual renewal")
  public ApiResponse<Map<String, Object>> renew(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID accountId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) RenewRequest body) {
    return ApiResponse.ok(
        renewalChurn.manualRenew(
            principal,
            accountId,
            body == null ? null : body.waiveFee(),
            body == null ? null : body.reason(),
            idempotencyKey));
  }

  @PostMapping("/accounts/{accountId}/churn-survey")
  @RequiresPermission("crm:write")
  @Operation(summary = "Admin: log churn survey")
  public ResponseEntity<ApiResponse<Map<String, Object>>> churnSurvey(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID accountId,
      @RequestBody(required = false) ChurnSurveyRequest body) {
    Map<String, Object> data =
        renewalChurn.logChurnSurvey(
            principal,
            accountId,
            body == null ? null : body.reason(),
            body == null ? null : body.notes());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @GetMapping("/churn-analysis")
  @RequiresPermission("crm:read")
  @Operation(summary = "Admin: churn analysis")
  public ApiResponse<Map<String, Object>> churnAnalysis(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String period) {
    return ApiResponse.ok(renewalChurn.churnAnalysis(principal, period));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RenewRequest(Boolean waiveFee, String reason) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ChurnSurveyRequest(String reason, String notes) {}
}

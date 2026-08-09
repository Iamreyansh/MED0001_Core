package com.nammamedmate.crm.adapter.in.web;

import com.nammamedmate.crm.application.SaasAnalyticsService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/crm/analytics")
@Tag(name = "Admin CRM SaaS revenue analytics")
public class AdminCrmAnalyticsController {

  private final SaasAnalyticsService analytics;

  public AdminCrmAnalyticsController(SaasAnalyticsService analytics) {
    this.analytics = analytics;
  }

  @GetMapping("/revenue")
  @RequiresPermission("crm:analytics")
  @Operation(summary = "Admin: SaaS revenue analytics dashboard")
  public ApiResponse<Map<String, Object>> revenue(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String period,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String plan) {
    return ApiResponse.ok(analytics.revenue(principal, period, from, to, plan));
  }

  @GetMapping("/mrr-bridge")
  @RequiresPermission("crm:analytics")
  @Operation(summary = "Admin: MRR movement bridge")
  public ApiResponse<Map<String, Object>> mrrBridge(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String month) {
    return ApiResponse.ok(analytics.mrrBridge(principal, month));
  }

  @GetMapping("/cohort")
  @RequiresPermission("crm:analytics")
  @Operation(summary = "Admin: cohort retention grid")
  public ApiResponse<Map<String, Object>> cohort(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "cohort_from", required = false) String cohortFrom,
      @RequestParam(name = "cohort_to", required = false) String cohortTo) {
    return ApiResponse.ok(analytics.cohort(principal, cohortFrom, cohortTo));
  }

  @GetMapping("/unit-economics")
  @RequiresPermission("crm:analytics")
  @Operation(summary = "Admin: SaaS unit economics")
  public ApiResponse<Map<String, Object>> unitEconomics(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(analytics.unitEconomics(principal));
  }

  @GetMapping("/report")
  @RequiresPermission("crm:analytics")
  @Operation(summary = "Admin: download SaaS analytics report (signed URL)")
  public ApiResponse<Map<String, Object>> report(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String period,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) String format) {
    return ApiResponse.ok(analytics.report(principal, period, month, format));
  }
}

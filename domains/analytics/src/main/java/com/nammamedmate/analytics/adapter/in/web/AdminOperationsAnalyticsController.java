package com.nammamedmate.analytics.adapter.in.web;

import com.nammamedmate.analytics.application.OperationsAnalyticsService;
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
@RequestMapping("/api/v1/admin/analytics/operations")
@Tag(name = "Admin operations & SLA analytics")
public class AdminOperationsAnalyticsController {

  private final OperationsAnalyticsService ops;

  public AdminOperationsAnalyticsController(OperationsAnalyticsService ops) {
    this.ops = ops;
  }

  @GetMapping
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: operations KPI cards")
  public ApiResponse<Map<String, Object>> operations(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String period,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to,
      @RequestParam(required = false) String zone_id) {
    return ApiResponse.ok(ops.operations(principal, period, date_from, date_to, zone_id));
  }

  @GetMapping("/fulfilment-funnel")
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: fulfilment funnel")
  public ApiResponse<Map<String, Object>> fulfilmentFunnel(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String period,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to,
      @RequestParam(required = false) String zone_id) {
    return ApiResponse.ok(ops.fulfilmentFunnel(principal, period, date_from, date_to, zone_id));
  }

  @GetMapping("/delivery-breakdown")
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: delivery time P50/P90 breakdown")
  public ApiResponse<Map<String, Object>> deliveryBreakdown(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String period,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to) {
    return ApiResponse.ok(ops.deliveryBreakdown(principal, period, date_from, date_to));
  }

  @GetMapping("/cancellations")
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: cancellation analysis")
  public ApiResponse<Map<String, Object>> cancellations(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String period,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to,
      @RequestParam(required = false) String zone_id) {
    return ApiResponse.ok(ops.cancellations(principal, period, date_from, date_to, zone_id));
  }
}

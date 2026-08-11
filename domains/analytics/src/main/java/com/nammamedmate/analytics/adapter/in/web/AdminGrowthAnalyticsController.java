package com.nammamedmate.analytics.adapter.in.web;

import com.nammamedmate.analytics.application.GrowthAnalyticsService;
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
@RequestMapping("/api/v1/admin/analytics/growth")
@Tag(name = "Admin growth & cohort analytics")
public class AdminGrowthAnalyticsController {

  private final GrowthAnalyticsService growth;

  public AdminGrowthAnalyticsController(GrowthAnalyticsService growth) {
    this.growth = growth;
  }

  @GetMapping
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: growth KPI cards")
  public ApiResponse<Map<String, Object>> growth(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String period,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to) {
    return ApiResponse.ok(growth.growth(principal, period, date_from, date_to));
  }

  @GetMapping("/cohort")
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: weekly retention cohort heatmap")
  public ApiResponse<Map<String, Object>> cohort(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) Integer cohort_count) {
    return ApiResponse.ok(growth.cohort(principal, cohort_count));
  }

  @GetMapping("/acquisition")
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: acquisition source breakdown")
  public ApiResponse<Map<String, Object>> acquisition(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String period,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to) {
    return ApiResponse.ok(growth.acquisition(principal, period, date_from, date_to));
  }

  @GetMapping("/order-trend")
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: order volume new vs returning trend")
  public ApiResponse<Map<String, Object>> orderTrend(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String period,
      @RequestParam(required = false) String granularity) {
    return ApiResponse.ok(growth.orderTrend(principal, period, granularity));
  }
}

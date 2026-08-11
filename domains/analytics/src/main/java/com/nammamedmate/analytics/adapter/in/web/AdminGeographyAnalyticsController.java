package com.nammamedmate.analytics.adapter.in.web;

import com.nammamedmate.analytics.application.GeographyAnalyticsService;
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
@RequestMapping("/api/v1/admin/analytics/geography")
@Tag(name = "Admin geography analytics")
public class AdminGeographyAnalyticsController {

  private final GeographyAnalyticsService geography;

  public AdminGeographyAnalyticsController(GeographyAnalyticsService geography) {
    this.geography = geography;
  }

  @GetMapping
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: zone-level geography KPIs")
  public ApiResponse<Map<String, Object>> geography(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String period,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String order) {
    return ApiResponse.ok(geography.geography(principal, period, sort, order));
  }

  @GetMapping("/supply-gap")
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: zone supply-demand gap analysis")
  public ApiResponse<Map<String, Object>> supplyGap(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String period,
      @RequestParam(required = false) String severity) {
    return ApiResponse.ok(geography.supplyGap(principal, period, severity));
  }

  @GetMapping("/demand-heatmap")
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: hourly demand heatmap (precomputed 28D)")
  public ApiResponse<Map<String, Object>> demandHeatmap(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String zone_id) {
    return ApiResponse.ok(geography.demandHeatmap(principal, zone_id));
  }
}

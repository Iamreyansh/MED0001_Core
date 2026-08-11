package com.nammamedmate.analytics.adapter.in.web;

import com.nammamedmate.analytics.application.PlatformOverviewService;
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
@RequestMapping("/api/v1/admin/analytics/overview")
@Tag(name = "Admin platform overview analytics")
public class AdminPlatformOverviewController {

  private final PlatformOverviewService overview;

  public AdminPlatformOverviewController(PlatformOverviewService overview) {
    this.overview = overview;
  }

  @GetMapping
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: platform overview KPI cards")
  public ApiResponse<Map<String, Object>> overview(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String period,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to) {
    return ApiResponse.ok(overview.overview(principal, period, date_from, date_to));
  }

  @GetMapping("/charts")
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: platform overview charts")
  public ApiResponse<Map<String, Object>> charts(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String period,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to) {
    return ApiResponse.ok(overview.charts(principal, period, date_from, date_to));
  }

  @GetMapping("/leaderboards")
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: top pharmacy and rider leaderboards")
  public ApiResponse<Map<String, Object>> leaderboards(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String period,
      @RequestParam(required = false) Integer top_n,
      @RequestParam(required = false) String export) {
    return ApiResponse.ok(overview.leaderboards(principal, period, top_n, export));
  }
}

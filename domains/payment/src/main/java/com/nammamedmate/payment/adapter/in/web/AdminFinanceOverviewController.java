package com.nammamedmate.payment.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.payment.application.FinanceOverviewService;
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
@RequestMapping("/api/v1/admin/finance")
@Tag(name = "Admin finance overview")
public class AdminFinanceOverviewController {

  private final FinanceOverviewService overview;

  public AdminFinanceOverviewController(FinanceOverviewService overview) {
    this.overview = overview;
  }

  @GetMapping("/kpi")
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin: real-time finance KPI chips")
  public ApiResponse<Map<String, Object>> kpi(@AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(overview.kpi(principal));
  }

  @GetMapping("/pnl")
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin: P&L summary for selected period")
  public ApiResponse<Map<String, Object>> pnl(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String period,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return ApiResponse.ok(overview.pnl(principal, period, from, to));
  }

  @GetMapping("/cash-position")
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin: cumulative cash position")
  public ApiResponse<Map<String, Object>> cashPosition(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String period,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return ApiResponse.ok(overview.cashPosition(principal, period, from, to));
  }

  @GetMapping("/ratios")
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin: key financial health ratios")
  public ApiResponse<Map<String, Object>> ratios(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String period,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return ApiResponse.ok(overview.ratios(principal, period, from, to));
  }
}

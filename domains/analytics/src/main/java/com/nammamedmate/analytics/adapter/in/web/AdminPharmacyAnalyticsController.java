package com.nammamedmate.analytics.adapter.in.web;

import com.nammamedmate.analytics.application.PharmacyAnalyticsService;
import com.nammamedmate.analytics.application.PharmacyAnalyticsService.PageResult;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin impersonation mirror of pharmacy analytics (ops/super). */
@RestController
@RequestMapping("/api/v1/admin/pharmacies/{pharmacyId}/analytics")
@Tag(name = "Admin pharmacy analytics")
public class AdminPharmacyAnalyticsController {

  private final PharmacyAnalyticsService service;

  public AdminPharmacyAnalyticsController(PharmacyAnalyticsService service) {
    this.service = service;
  }

  @GetMapping("/overview")
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: pharmacy analytics overview")
  public ApiResponse<Map<String, Object>> overview(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID pharmacyId,
      @RequestParam String period,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to) {
    return ApiResponse.ok(service.overview(principal, pharmacyId, period, date_from, date_to));
  }

  @GetMapping("/sales-register")
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: pharmacy sales register")
  public ApiResponse<Map<String, Object>> salesRegister(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID pharmacyId,
      @RequestParam String period,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to,
      @RequestParam(required = false) String channel,
      @RequestParam(required = false) String payment_method,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    PageResult result =
        service.salesRegister(
            principal,
            pharmacyId,
            period,
            date_from,
            date_to,
            channel,
            payment_method,
            page,
            limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/products")
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: pharmacy product analytics")
  public ApiResponse<Map<String, Object>> products(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID pharmacyId,
      @RequestParam String period,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String order,
      @RequestParam(required = false) Boolean dead_stock_only,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    PageResult result =
        service.products(
            principal,
            pharmacyId,
            period,
            date_from,
            date_to,
            sort,
            order,
            dead_stock_only,
            page,
            limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/accounts-gst")
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: pharmacy accounts/GST")
  public ApiResponse<Map<String, Object>> accountsGst(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID pharmacyId,
      @RequestParam String period,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to) {
    return ApiResponse.ok(service.accountsGst(principal, pharmacyId, period, date_from, date_to));
  }

  @GetMapping("/reports-catalogue")
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: pharmacy report catalogue")
  public ApiResponse<Map<String, Object>> catalogue(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID pharmacyId) {
    return ApiResponse.ok(service.reportsCatalogue(principal, pharmacyId));
  }

  @GetMapping("/reports/{reportId}")
  @RequiresPermission("analytics:read")
  @Operation(summary = "Admin: run pharmacy report")
  public ApiResponse<Map<String, Object>> report(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID pharmacyId,
      @PathVariable String reportId,
      @RequestParam String period,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to,
      @RequestParam(required = false) String export) {
    return ApiResponse.ok(
        service.runReport(principal, pharmacyId, reportId, period, date_from, date_to, export));
  }
}

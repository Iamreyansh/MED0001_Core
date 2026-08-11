package com.nammamedmate.analytics.adapter.in.web;

import com.nammamedmate.analytics.application.PharmacyAnalyticsService;
import com.nammamedmate.analytics.application.PharmacyAnalyticsService.PageResult;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/analytics")
@Tag(name = "Pharmacy analytics")
public class PharmacyAnalyticsController {

  private final PharmacyAnalyticsService service;

  public PharmacyAnalyticsController(PharmacyAnalyticsService service) {
    this.service = service;
  }

  @GetMapping("/overview")
  @Operation(summary = "Pharmacy analytics KPI overview")
  public ApiResponse<Map<String, Object>> overview(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String period,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to) {
    return ApiResponse.ok(service.overview(principal, null, period, date_from, date_to));
  }

  @GetMapping("/sales-register")
  @Operation(summary = "Pharmacy sales register")
  public ApiResponse<Map<String, Object>> salesRegister(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String period,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to,
      @RequestParam(required = false) String channel,
      @RequestParam(required = false) String payment_method,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    PageResult result =
        service.salesRegister(
            principal, null, period, date_from, date_to, channel, payment_method, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/products")
  @Operation(summary = "Pharmacy product analytics")
  public ApiResponse<Map<String, Object>> products(
      @AuthenticationPrincipal MedmatePrincipal principal,
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
            principal, null, period, date_from, date_to, sort, order, dead_stock_only, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/accounts-gst")
  @Operation(summary = "Pharmacy P&L, GST, day book (owner only)")
  public ApiResponse<Map<String, Object>> accountsGst(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String period,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to) {
    return ApiResponse.ok(service.accountsGst(principal, null, period, date_from, date_to));
  }

  @GetMapping("/reports-catalogue")
  @Operation(summary = "Pharmacy report catalogue")
  public ApiResponse<Map<String, Object>> catalogue(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.reportsCatalogue(principal, null));
  }

  @GetMapping("/reports/{reportId}")
  @Operation(summary = "Run pharmacy report (optional excel/pdf export)")
  public ApiResponse<Map<String, Object>> report(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable String reportId,
      @RequestParam String period,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to,
      @RequestParam(required = false) String export) {
    return ApiResponse.ok(
        service.runReport(principal, null, reportId, period, date_from, date_to, export));
  }

  @PatchMapping("/reports/{reportId}/favorite")
  @Operation(summary = "Toggle report favorite (owner only)")
  public ApiResponse<Map<String, Object>> favorite(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable String reportId,
      @RequestBody Map<String, Object> body) {
    Boolean isFavorite =
        body == null || body.get("is_favorite") == null
            ? null
            : Boolean.valueOf(body.get("is_favorite").toString());
    return ApiResponse.ok(service.setFavorite(principal, null, reportId, isFavorite));
  }
}

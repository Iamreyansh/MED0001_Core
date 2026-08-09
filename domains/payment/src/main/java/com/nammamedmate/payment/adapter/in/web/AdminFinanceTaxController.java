package com.nammamedmate.payment.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.payment.application.TaxFacadeService;
import com.nammamedmate.payment.application.TaxFacadeService.PagedResult;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/finance/taxes")
@Tag(name = "Admin tax & GST finance")
public class AdminFinanceTaxController {

  private final TaxFacadeService taxes;

  public AdminFinanceTaxController(TaxFacadeService taxes) {
    this.taxes = taxes;
  }

  @GetMapping
  @RequiresPermission("taxes:read")
  @Operation(summary = "Admin: monthly tax liability panel")
  public ApiResponse<Map<String, Object>> panel(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String month) {
    return ApiResponse.ok(taxes.taxPanel(principal, month));
  }

  @GetMapping("/filings")
  @RequiresPermission("taxes:read")
  @Operation(summary = "Admin: tax filings tracker")
  public ApiResponse<Map<String, Object>> filings(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) Integer year,
      @RequestParam(required = false) String status) {
    return ApiResponse.ok(taxes.listFilings(principal, year, status));
  }

  @PostMapping("/filings/{filingId}/generate")
  @RequiresPermission("taxes:export")
  @Operation(summary = "Admin: generate GSTR-8 / TDS filing export")
  public ApiResponse<Map<String, Object>> generate(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID filingId,
      @RequestBody(required = false) GenerateRequest body) {
    String format = body == null ? "JSON" : body.format();
    return ApiResponse.ok(taxes.generate(principal, filingId, format));
  }

  @PostMapping("/filings/{filingId}/mark-filed")
  @RequiresPermission("taxes:export")
  @Operation(summary = "Admin: mark tax filing as submitted")
  public ApiResponse<Map<String, Object>> markFiled(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID filingId,
      @RequestBody MarkFiledRequest body) {
    Instant filedAt = body == null ? null : body.filedAt();
    String reference = body == null ? null : body.referenceNumber();
    String notes = body == null ? null : body.notes();
    return ApiResponse.ok(taxes.markFiled(principal, filingId, filedAt, reference, notes));
  }

  @GetMapping("/tcs-register")
  @RequiresPermission("taxes:read")
  @Operation(summary = "Admin: TCS register per pharmacy per month")
  public ApiResponse<Map<String, Object>> tcsRegister(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String month,
      @RequestParam(name = "pharmacy_id", required = false) UUID pharmacyId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    PagedResult result = taxes.tcsRegister(principal, month, pharmacyId, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record GenerateRequest(String format) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record MarkFiledRequest(Instant filedAt, String referenceNumber, String notes) {}
}

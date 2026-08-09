package com.nammamedmate.payment.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.payment.application.RefundFacadeService;
import com.nammamedmate.payment.application.RefundFacadeService.PagedResult;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/finance/refunds")
@Tag(name = "Admin finance refunds")
public class AdminFinanceRefundController {

  private final RefundFacadeService refunds;

  public AdminFinanceRefundController(RefundFacadeService refunds) {
    this.refunds = refunds;
  }

  @GetMapping
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin: refund queue with KPI chips")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(value = "refund_to", required = false) String refundTo,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    PagedResult result = refunds.listAdmin(principal, status, refundTo, from, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/{refundId}")
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin: refund detail")
  public ApiResponse<Map<String, Object>> detail(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID refundId) {
    return ApiResponse.ok(refunds.getAdminDetail(principal, refundId));
  }

  @PostMapping("/{refundId}/process")
  @RequiresPermission("finance:update")
  @Operation(summary = "Admin: process a PENDING refund")
  public ApiResponse<Map<String, Object>> process(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID refundId,
      @RequestBody(required = false) ProcessRequest body) {
    ProcessRequest req = body == null ? new ProcessRequest(null) : body;
    return ApiResponse.ok(refunds.process(principal, refundId, req.notes()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ProcessRequest(String notes) {}
}

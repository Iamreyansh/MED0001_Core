package com.nammamedmate.rider.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.rider.application.CodReconciliationService;
import com.nammamedmate.rider.application.CodReconciliationService.BoardResult;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@RequestMapping("/api/v1/admin/finance/cod")
@Tag(name = "Admin COD finance")
public class AdminFinanceCodController {

  private final CodReconciliationService service;

  public AdminFinanceCodController(CodReconciliationService service) {
    this.service = service;
  }

  @GetMapping
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin: COD reconciliation board")
  public ApiResponse<Map<String, Object>> board(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "zone_id", required = false) UUID zoneId,
      @RequestParam(name = "risk_only", required = false) Boolean riskOnly,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    BoardResult result = service.adminBoard(principal, zoneId, riskOnly, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/{rider_id}/mark-deposited")
  @RequiresPermission("finance:update")
  @Operation(summary = "Admin finance: confirm COD deposit")
  public ApiResponse<Map<String, Object>> markDeposited(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("rider_id") UUID riderId,
      @RequestBody(required = false) MarkDepositedRequest body) {
    return ApiResponse.ok(
        service.markDeposited(
            principal,
            riderId,
            body == null ? null : body.amount(),
            body == null ? null : body.depositedAt(),
            body == null ? null : body.referenceNumber(),
            body == null ? null : body.notes()));
  }

  @PostMapping("/{rider_id}/remind")
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin: send COD deposit reminder")
  public ApiResponse<Map<String, Object>> remind(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("rider_id") UUID riderId,
      @RequestBody(required = false) RemindRequest body) {
    return ApiResponse.ok(service.remind(principal, riderId, body == null ? null : body.message()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record MarkDepositedRequest(
      Object amount, String depositedAt, String referenceNumber, String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RemindRequest(String message) {}
}

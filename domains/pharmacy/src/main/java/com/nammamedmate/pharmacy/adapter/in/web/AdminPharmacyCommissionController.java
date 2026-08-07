package com.nammamedmate.pharmacy.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pharmacy.application.AdminPharmacyCommissionService;
import com.nammamedmate.pharmacy.application.AdminPharmacySettlementService;
import com.nammamedmate.pharmacy.application.AdminPharmacySettlementService.PagedResult;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/pharmacies")
@Tag(name = "Admin pharmacy commission & settlements")
public class AdminPharmacyCommissionController {

  private final AdminPharmacyCommissionService commissionService;
  private final AdminPharmacySettlementService settlementService;

  public AdminPharmacyCommissionController(
      AdminPharmacyCommissionService commissionService,
      AdminPharmacySettlementService settlementService) {
    this.commissionService = commissionService;
    this.settlementService = settlementService;
  }

  @GetMapping("/{id}/commission")
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: pharmacy commission details")
  public ApiResponse<Map<String, Object>> getCommission(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(commissionService.getCommission(principal, id));
  }

  @PatchMapping("/{id}/commission")
  @RequiresPermission("finance:update")
  @Operation(summary = "Admin: schedule commission tier change")
  public ApiResponse<Map<String, Object>> changeCommission(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) ChangeCommissionRequest body,
      jakarta.servlet.http.HttpServletRequest request) {
    ChangeCommissionRequest req =
        body == null ? new ChangeCommissionRequest(null, null, null, null) : body;
    return ApiResponse.ok(
        commissionService.changeCommission(
            principal,
            id,
            req.commissionPct(),
            req.effectiveFrom(),
            req.reason(),
            req.notes(),
            clientIp(request)));
  }

  @GetMapping("/{id}/settlements")
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: pharmacy settlement history")
  public ApiResponse<Map<String, Object>> listSettlements(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestParam(required = false) String status,
      @RequestParam(name = "from_date", required = false) LocalDate fromDate,
      @RequestParam(name = "to_date", required = false) LocalDate toDate,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    PagedResult result =
        settlementService.listSettlements(principal, id, status, fromDate, toDate, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/{id}/settlements/{settlementId}/release")
  @RequiresPermission("settlements:process")
  @Operation(summary = "Admin: release settlement payout")
  public ApiResponse<Map<String, Object>> releaseSettlement(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @PathVariable UUID settlementId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) ReleaseSettlementRequest body) {
    ReleaseSettlementRequest req = body == null ? new ReleaseSettlementRequest(null) : body;
    return ApiResponse.ok(
        settlementService.release(principal, id, settlementId, req.notes(), idempotencyKey));
  }

  @PostMapping("/{id}/settlements/{settlementId}/hold")
  @RequiresPermission("settlements:process")
  @Operation(summary = "Admin: hold settlement payout")
  public ApiResponse<Map<String, Object>> holdSettlement(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @PathVariable UUID settlementId,
      @RequestBody(required = false) HoldSettlementRequest body) {
    HoldSettlementRequest req = body == null ? new HoldSettlementRequest(null) : body;
    return ApiResponse.ok(settlementService.hold(principal, id, settlementId, req.reason()));
  }

  private static String clientIp(jakarta.servlet.http.HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    return remote == null || remote.isBlank() ? null : remote.trim();
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ChangeCommissionRequest(
      BigDecimal commissionPct, LocalDate effectiveFrom, String reason, String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReleaseSettlementRequest(String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record HoldSettlementRequest(String reason) {}
}

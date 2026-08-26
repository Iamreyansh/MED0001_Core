package com.nammamedmate.payment.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.payment.application.SettlementFacadeService;
import com.nammamedmate.payment.application.SettlementFacadeService.PagedResult;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/finance/settlements")
@Tag(name = "Admin finance settlements")
public class AdminFinanceSettlementController {

  private final SettlementFacadeService settlements;

  public AdminFinanceSettlementController(SettlementFacadeService settlements) {
    this.settlements = settlements;
  }

  @GetMapping
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin: list pharmacy settlements with KPIs")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(value = "pharmacy_id", required = false) UUID pharmacyId,
      @RequestParam(value = "cycle_from", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate cycleFrom,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    PagedResult result =
        settlements.listAdmin(principal, status, pharmacyId, cycleFrom, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/{settlementId}")
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin: settlement detail with line items")
  public ApiResponse<Map<String, Object>> detail(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID settlementId) {
    return ApiResponse.ok(settlements.getAdminDetail(principal, settlementId));
  }

  @PostMapping("/{settlementId}/release")
  @RequiresPermission("settlements:process")
  @Operation(summary = "Admin: release settlement via CashfreePayout")
  public ApiResponse<Map<String, Object>> release(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID settlementId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) ReleaseRequest body) {
    ReleaseRequest req = body == null ? new ReleaseRequest(null) : body;
    return ApiResponse.ok(
        settlements.release(principal, settlementId, req.notes(), idempotencyKey));
  }

  @PostMapping("/{settlementId}/hold")
  @RequiresPermission("settlements:process")
  @Operation(summary = "Admin: hold settlement")
  public ApiResponse<Map<String, Object>> hold(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID settlementId,
      @RequestBody(required = false) HoldRequest body) {
    HoldRequest req = body == null ? new HoldRequest(null, null) : body;
    return ApiResponse.ok(settlements.hold(principal, settlementId, req.reason(), req.notes()));
  }

  @PostMapping("/{settlementId}/unhold")
  @RequiresPermission("settlements:process")
  @Operation(summary = "Admin: release hold so settlement can be paid")
  public ApiResponse<Map<String, Object>> unhold(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID settlementId,
      @RequestBody(required = false) HoldRequest body) {
    HoldRequest req = body == null ? new HoldRequest(null, null) : body;
    return ApiResponse.ok(settlements.unhold(principal, settlementId, req.notes()));
  }

  @PostMapping("/release-all")
  @RequiresPermission("settlements:process")
  @Operation(summary = "Admin: bulk-release PENDING settlements ≤ threshold")
  public ApiResponse<Map<String, Object>> releaseAll(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) ReleaseAllRequest body) {
    ReleaseAllRequest req = body == null ? new ReleaseAllRequest(null, null) : body;
    return ApiResponse.ok(
        settlements.releaseAll(principal, req.threshold(), req.notes(), idempotencyKey));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReleaseRequest(String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record HoldRequest(String reason, String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReleaseAllRequest(Object threshold, String notes) {}
}

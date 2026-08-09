package com.nammamedmate.payment.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.payment.application.RiderPayoutFacadeService;
import com.nammamedmate.payment.application.RiderPayoutFacadeService.PagedResult;
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
@RequestMapping("/api/v1/admin/finance/rider-payouts")
@Tag(name = "Admin finance rider payouts")
public class AdminFinanceRiderPayoutController {

  private final RiderPayoutFacadeService payouts;

  public AdminFinanceRiderPayoutController(RiderPayoutFacadeService payouts) {
    this.payouts = payouts;
  }

  @GetMapping
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin: list rider payouts with cycle summary")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "cycle_from", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate cycleFrom,
      @RequestParam(required = false) String status,
      @RequestParam(value = "zone_id", required = false) UUID zoneId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    PagedResult result = payouts.listAdmin(principal, cycleFrom, status, zoneId, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/{riderId}/ledger")
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin: rider earnings ledger for a cycle")
  public ApiResponse<Map<String, Object>> ledger(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID riderId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    PagedResult result = payouts.ledger(principal, riderId, from, to, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/{riderId}/release")
  @RequiresPermission("finance:update")
  @Operation(summary = "Admin: release rider payout via Razorpay Route")
  public ApiResponse<Map<String, Object>> release(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID riderId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) ReleaseRequest body) {
    ReleaseRequest req = body == null ? new ReleaseRequest(null, null) : body;
    return ApiResponse.ok(
        payouts.release(principal, riderId, req.payoutId(), req.notes(), idempotencyKey));
  }

  @PostMapping("/release-all")
  @RequiresPermission("finance:update")
  @Operation(summary = "Admin: bulk-release PENDING rider payouts ≤ threshold")
  public ApiResponse<Map<String, Object>> releaseAll(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) ReleaseAllRequest body) {
    ReleaseAllRequest req = body == null ? new ReleaseAllRequest(null, null, null) : body;
    return ApiResponse.ok(
        payouts.releaseAll(
            principal, req.threshold(), req.cycleFrom(), req.notes(), idempotencyKey));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReleaseRequest(UUID payoutId, String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReleaseAllRequest(Object threshold, LocalDate cycleFrom, String notes) {}
}

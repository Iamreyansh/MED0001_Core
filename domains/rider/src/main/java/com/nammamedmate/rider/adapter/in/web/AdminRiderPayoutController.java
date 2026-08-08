package com.nammamedmate.rider.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.rider.application.RiderEarningsService;
import com.nammamedmate.rider.application.RiderEarningsService.LedgerResult;
import com.nammamedmate.rider.application.RiderPayoutService;
import com.nammamedmate.rider.application.RiderPerformanceService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/riders")
@Tag(name = "Admin rider payouts & performance")
public class AdminRiderPayoutController {

  private final RiderEarningsService earnings;
  private final RiderPerformanceService performance;
  private final RiderPayoutService payouts;

  public AdminRiderPayoutController(
      RiderEarningsService earnings,
      RiderPerformanceService performance,
      RiderPayoutService payouts) {
    this.earnings = earnings;
    this.performance = performance;
    this.payouts = payouts;
  }

  @GetMapping("/{id}/earnings-ledger")
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin finance: rider payout ledger")
  public ApiResponse<Map<String, Object>> ledger(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    LedgerResult result = earnings.adminLedger(principal, id, page, limit, from, to);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/{id}/performance")
  @RequiresPermission("finance:read")
  @Operation(summary = "Admin: rider performance profile")
  public ApiResponse<Map<String, Object>> performance(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(performance.adminPerformance(principal, id));
  }

  @PostMapping("/{id}/payout/release")
  @RequiresPermission("finance:update")
  @Operation(summary = "Admin finance: release rider payout")
  public ApiResponse<Map<String, Object>> release(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) ReleaseRequest body) {
    return ApiResponse.ok(
        payouts.release(
            principal,
            id,
            body == null ? null : body.payoutId(),
            body == null ? null : body.notes(),
            idempotencyKey));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReleaseRequest(UUID payoutId, String notes) {}
}

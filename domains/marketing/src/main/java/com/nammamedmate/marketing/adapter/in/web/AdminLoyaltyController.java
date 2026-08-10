package com.nammamedmate.marketing.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.marketing.application.port.out.LoyaltyAdminPort;
import com.nammamedmate.marketing.application.port.out.LoyaltyAdminPort.AdjustCommand;
import com.nammamedmate.marketing.application.port.out.LoyaltyAdminPort.PatchProgramCommand;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/loyalty")
@Tag(name = "Admin loyalty")
public class AdminLoyaltyController {

  private final LoyaltyAdminPort loyalty;

  public AdminLoyaltyController(LoyaltyAdminPort loyalty) {
    this.loyalty = loyalty;
  }

  @GetMapping("/program")
  @Operation(summary = "Get loyalty program settings")
  public ApiResponse<Map<String, Object>> getProgram(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(loyalty.getProgram(principal));
  }

  @PatchMapping("/program")
  @Operation(summary = "Update loyalty program settings (admin_super)")
  public ApiResponse<Map<String, Object>> patchProgram(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) PatchProgramRequest body) {
    PatchProgramRequest req =
        body == null
            ? new PatchProgramRequest(null, null, null, null, null, null, null, null)
            : body;
    return ApiResponse.ok(
        loyalty.patchProgram(
            principal,
            new PatchProgramCommand(
                req.earnRateRsPerPoint(),
                req.redemptionRateRsPerPoint(),
                req.tierSilverPts(),
                req.tierGoldPts(),
                req.tierPlatinumPts(),
                req.maxRedemptionPctPerOrder(),
                req.minPointsPerRedemption(),
                req.pointsExpiryDays())));
  }

  @GetMapping("/overview")
  @Operation(summary = "Loyalty overview KPIs")
  public ApiResponse<Map<String, Object>> overview(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(loyalty.overview(principal));
  }

  @PostMapping("/customers/{customerId}/adjust")
  @Operation(summary = "Manually adjust customer loyalty points (admin_super)")
  public ApiResponse<Map<String, Object>> adjust(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable String customerId,
      @RequestBody(required = false) AdjustRequest body) {
    UUID id;
    try {
      id = Ids.parse(customerId);
    } catch (RuntimeException ex) {
      throw new AppException("VALIDATION_ERROR", "customer_id must be a UUID", 400);
    }
    AdjustRequest req = body == null ? new AdjustRequest(null, null, null) : body;
    return ApiResponse.ok(
        loyalty.adjust(
            principal, id, new AdjustCommand(req.points(), req.reason(), req.referenceOrderId())));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PatchProgramRequest(
      Integer earnRateRsPerPoint,
      Number redemptionRateRsPerPoint,
      Integer tierSilverPts,
      Integer tierGoldPts,
      Integer tierPlatinumPts,
      Integer maxRedemptionPctPerOrder,
      Integer minPointsPerRedemption,
      Integer pointsExpiryDays) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AdjustRequest(Integer points, String reason, String referenceOrderId) {}
}

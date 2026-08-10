package com.nammamedmate.marketing.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.marketing.application.port.out.ReferralAdminPort;
import com.nammamedmate.marketing.application.port.out.ReferralAdminPort.OverviewResult;
import com.nammamedmate.marketing.application.port.out.ReferralAdminPort.PatchProgramCommand;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/referrals")
@Tag(name = "Admin referrals")
public class AdminReferralController {

  private final ReferralAdminPort referrals;

  public AdminReferralController(ReferralAdminPort referrals) {
    this.referrals = referrals;
  }

  @GetMapping
  @Operation(summary = "Referral overview: chips, top referrers, table")
  public ApiResponse<Map<String, Object>> overview(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    OverviewResult result = referrals.overview(principal, status, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/program")
  @Operation(summary = "Get referral program settings")
  public ApiResponse<Map<String, Object>> getProgram(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(referrals.getProgram(principal));
  }

  @PatchMapping("/program")
  @Operation(summary = "Update referral program settings (admin_super)")
  public ApiResponse<Map<String, Object>> patchProgram(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) PatchProgramRequest body) {
    PatchProgramRequest req =
        body == null ? new PatchProgramRequest(null, null, null, null, null) : body;
    return ApiResponse.ok(
        referrals.patchProgram(
            principal,
            new PatchProgramCommand(
                req.rewardForReferrerRs(),
                req.rewardForRefereeRs(),
                req.isActive(),
                req.rewardExpiryDays(),
                req.conditions())));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PatchProgramRequest(
      Number rewardForReferrerRs,
      Number rewardForRefereeRs,
      Boolean isActive,
      Integer rewardExpiryDays,
      String conditions) {}
}

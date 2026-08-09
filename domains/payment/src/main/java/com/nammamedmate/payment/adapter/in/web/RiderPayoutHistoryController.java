package com.nammamedmate.payment.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.payment.application.RiderPayoutFacadeService;
import com.nammamedmate.payment.application.RiderPayoutFacadeService.PagedResult;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rider/payouts")
@Tag(name = "Rider payout history")
public class RiderPayoutHistoryController {

  private final RiderPayoutFacadeService payouts;

  public RiderPayoutHistoryController(RiderPayoutFacadeService payouts) {
    this.payouts = payouts;
  }

  @GetMapping("/history")
  @Operation(summary = "Rider: own payout history")
  public ApiResponse<Map<String, Object>> history(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    PagedResult result = payouts.history(principal, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }
}

package com.nammamedmate.integration.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.integration.application.InternalServiceAuth;
import com.nammamedmate.integration.application.RazorpayIntegrationService;
import com.nammamedmate.kernel.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/razorpayx")
@Tag(name = "RazorpayX payouts (S2S)")
public class RazorpayXPayoutController {

  private final RazorpayIntegrationService service;
  private final InternalServiceAuth internalAuth;

  public RazorpayXPayoutController(
      RazorpayIntegrationService service, InternalServiceAuth internalAuth) {
    this.service = service;
    this.internalAuth = internalAuth;
  }

  @PostMapping("/payout")
  @Operation(summary = "Initiate RazorpayX payout (internal token)")
  public ApiResponse<Map<String, Object>> payout(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody(required = false) PayoutRequest body) {
    internalAuth.require(internalToken);
    PayoutRequest req = body == null ? new PayoutRequest(null, 0, null, null, null, null) : body;
    return ApiResponse.ok(
        service.initiatePayout(
            req.fundAccountId(),
            req.amountPaise(),
            req.mode(),
            req.purpose(),
            req.referenceId(),
            req.notes()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PayoutRequest(
      String fundAccountId,
      long amountPaise,
      String mode,
      String purpose,
      String referenceId,
      Map<String, String> notes) {
    public PayoutRequest {
      notes = notes == null ? null : Map.copyOf(notes);
    }
  }
}

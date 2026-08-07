package com.nammamedmate.pharmacy.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.webhook.WebhookRawBodyFilter;
import com.nammamedmate.pharmacy.application.AdminPharmacySettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks/razorpayx")
@Tag(name = "RazorpayX webhooks")
public class RazorpayXPayoutWebhookController {

  private final AdminPharmacySettlementService settlementService;

  public RazorpayXPayoutWebhookController(AdminPharmacySettlementService settlementService) {
    this.settlementService = settlementService;
  }

  @PostMapping("/payout")
  @ResponseStatus(HttpStatus.OK)
  @Operation(summary = "RazorpayX payout status webhook")
  public ApiResponse<Map<String, Object>> payoutWebhook(
      @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
      HttpServletRequest request) {
    byte[] rawBody = WebhookRawBodyFilter.rawBody(request);
    return ApiResponse.ok(settlementService.handlePayoutWebhook(signature, rawBody));
  }
}

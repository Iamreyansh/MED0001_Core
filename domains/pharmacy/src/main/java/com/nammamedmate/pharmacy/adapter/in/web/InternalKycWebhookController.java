package com.nammamedmate.pharmacy.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.webhook.WebhookRawBodyFilter;
import com.nammamedmate.pharmacy.application.AutoKycService;
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
@RequestMapping("/api/v1/internal/kyc")
@Tag(name = "Internal KYC webhooks")
public class InternalKycWebhookController {

  private final AutoKycService autoKyc;

  public InternalKycWebhookController(AutoKycService autoKyc) {
    this.autoKyc = autoKyc;
  }

  @PostMapping("/webhook-callback")
  @ResponseStatus(HttpStatus.OK)
  @Operation(summary = "Internal: drug licence / FSSAI async verification callback")
  public ApiResponse<Map<String, Object>> webhookCallback(
      @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
      HttpServletRequest request) {
    byte[] rawBody = WebhookRawBodyFilter.rawBody(request);
    return ApiResponse.ok(autoKyc.handleWebhookCallback(signature, rawBody));
  }
}

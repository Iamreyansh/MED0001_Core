package com.nammamedmate.integration.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.integration.application.InternalServiceAuth;
import com.nammamedmate.integration.application.RazorpayIntegrationService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.webhook.WebhookRawBodyFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/razorpay")
@Tag(name = "Razorpay integration (S2S)")
public class RazorpayIntegrationController {

  private final RazorpayIntegrationService service;
  private final InternalServiceAuth internalAuth;

  public RazorpayIntegrationController(
      RazorpayIntegrationService service, InternalServiceAuth internalAuth) {
    this.service = service;
    this.internalAuth = internalAuth;
  }

  @PostMapping("/create-order")
  @Operation(summary = "Create Razorpay order (internal token)")
  public ApiResponse<Map<String, Object>> createOrder(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody(required = false) CreateOrderRequest body) {
    internalAuth.require(internalToken);
    CreateOrderRequest req = body == null ? new CreateOrderRequest(0, null, null, null) : body;
    return ApiResponse.ok(
        service.createOrder(req.amountPaise(), req.currency(), req.receipt(), req.notes()));
  }

  @PostMapping("/webhook")
  @ResponseStatus(HttpStatus.OK)
  @Operation(summary = "Razorpay webhook (HMAC X-Razorpay-Signature)")
  public ApiResponse<Void> webhook(
      @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
      HttpServletRequest request) {
    service.handleWebhook(signature, WebhookRawBodyFilter.rawBody(request));
    return ApiResponse.ok(null);
  }

  @PostMapping("/verify-upi")
  @Operation(summary = "Verify UPI VPA (internal token)")
  public ApiResponse<Map<String, Object>> verifyUpi(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody(required = false) VerifyUpiRequest body) {
    internalAuth.require(internalToken);
    String vpa = body == null ? null : body.vpa();
    return ApiResponse.ok(service.verifyUpi(vpa));
  }

  @PostMapping("/fund-account")
  @Operation(summary = "Create RazorpayX fund account (internal token)")
  public ApiResponse<Map<String, Object>> fundAccount(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody(required = false) FundAccountRequest body) {
    internalAuth.require(internalToken);
    FundAccountRequest req =
        body == null ? new FundAccountRequest(null, null, null, null, null, null) : body;
    return ApiResponse.ok(
        service.createFundAccount(
            req.entityType(),
            req.entityId(),
            req.bankName(),
            req.accountNumber(),
            req.ifsc(),
            req.accountHolderName()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateOrderRequest(
      long amountPaise, String currency, String receipt, Map<String, String> notes) {
    public CreateOrderRequest {
      notes = notes == null ? null : Map.copyOf(notes);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record VerifyUpiRequest(String vpa) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record FundAccountRequest(
      String entityType,
      UUID entityId,
      String bankName,
      String accountNumber,
      String ifsc,
      String accountHolderName) {}
}

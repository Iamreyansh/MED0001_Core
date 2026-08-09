package com.nammamedmate.payment.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.payment.application.PaymentService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments")
public class PaymentController {

  private final PaymentService payments;

  public PaymentController(PaymentService payments) {
    this.payments = payments;
  }

  @PostMapping("/initiate")
  @Operation(summary = "Initiate Razorpay payment for an order")
  public ResponseEntity<ApiResponse<Map<String, Object>>> initiate(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) InitiateRequest body) {
    InitiateRequest req = body == null ? new InitiateRequest(null, null, null, null) : body;
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ApiResponse.ok(
                payments.initiate(
                    principal, req.orderId(), req.amountPaise(), req.currency(), req.method())));
  }

  @PostMapping("/verify")
  @Operation(summary = "Verify Razorpay payment signature after checkout")
  public ApiResponse<Map<String, Object>> verify(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) VerifyRequest body) {
    VerifyRequest req = body == null ? new VerifyRequest(null, null, null) : body;
    return ApiResponse.ok(
        payments.verify(
            principal, req.razorpayPaymentId(), req.razorpayOrderId(), req.razorpaySignature()));
  }

  @GetMapping("/{paymentId}")
  @Operation(summary = "Get payment detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("paymentId") UUID paymentId) {
    return ApiResponse.ok(payments.getPayment(principal, paymentId));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record InitiateRequest(UUID orderId, Long amountPaise, String currency, String method) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record VerifyRequest(
      String razorpayPaymentId, String razorpayOrderId, String razorpaySignature) {}
}

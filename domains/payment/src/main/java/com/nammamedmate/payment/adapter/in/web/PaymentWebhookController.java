package com.nammamedmate.payment.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.webhook.WebhookRawBodyFilter;
import com.nammamedmate.payment.application.PaymentService;
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
@RequestMapping("/api/v1/payments/webhook")
@Tag(name = "Razorpay payment webhooks")
public class PaymentWebhookController {

  private final PaymentService payments;

  public PaymentWebhookController(PaymentService payments) {
    this.payments = payments;
  }

  @PostMapping("/razorpay")
  @ResponseStatus(HttpStatus.OK)
  @Operation(
      summary = "Razorpay payment.captured / payment.failed / refund.processed webhook",
      description = "HMAC via X-Razorpay-Signature. Idempotent on razorpay_payment_id.")
  public ApiResponse<Map<String, Object>> razorpay(
      @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
      HttpServletRequest request) {
    byte[] rawBody = WebhookRawBodyFilter.rawBody(request);
    return ApiResponse.ok(payments.handleWebhook(signature, rawBody));
  }
}

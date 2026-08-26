package com.nammamedmate.payment.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.webhook.WebhookRawBodyFilter;
import com.nammamedmate.messaging.WebhookInbox;
import com.nammamedmate.payment.application.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final ObjectProvider<WebhookInbox> inbox;
  private final ObjectMapper objectMapper;

  public PaymentWebhookController(PaymentService payments) {
    this(payments, null, new ObjectMapper());
  }

  @Autowired
  public PaymentWebhookController(
      PaymentService payments, ObjectProvider<WebhookInbox> inbox, ObjectMapper objectMapper) {
    this.payments = payments;
    this.inbox = inbox;
    this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
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
    WebhookInbox box = inbox == null ? null : inbox.getIfAvailable();
    String eventId = eventId(rawBody);
    if (box != null && eventId != null && box.alreadyReceived("razorpay", eventId)) {
      return ApiResponse.ok(Map.of("event", "duplicate", "processed", false));
    }
    Map<String, Object> data = payments.handleWebhook(signature, rawBody);
    if (box != null && eventId != null) {
      box.claim("razorpay", eventId, new String(rawBody, java.nio.charset.StandardCharsets.UTF_8));
    }
    return ApiResponse.ok(data);
  }

  private String eventId(byte[] rawBody) {
    try {
      JsonNode root = objectMapper.readTree(rawBody);
      JsonNode id = root.get("id");
      return id == null || id.isNull() ? null : id.asText();
    } catch (Exception e) {
      return null;
    }
  }
}

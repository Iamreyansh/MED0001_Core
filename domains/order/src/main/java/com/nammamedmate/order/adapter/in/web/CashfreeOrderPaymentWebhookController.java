package com.nammamedmate.order.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.webhook.WebhookRawBodyFilter;
import com.nammamedmate.order.application.port.out.CashfreePaymentPort;
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
@RequestMapping("/api/v1/webhooks/cashfree")
@Tag(name = "Cashfree order payment webhooks")
public class CashfreeOrderPaymentWebhookController {

  private final CashfreePaymentPort cashfree;

  public CashfreeOrderPaymentWebhookController(CashfreePaymentPort cashfree) {
    this.cashfree = cashfree;
  }

  @PostMapping("/order-payment")
  @ResponseStatus(HttpStatus.OK)
  @Operation(
      summary = "Cashfree payment.captured / refund.processed webhook for orders",
      description =
          "HMAC via x-webhook-signature. Idempotent on payment_id / refund id in payload;"
              + " Idempotency-Key is optional and unused.")
  public ApiResponse<Map<String, Object>> orderPayment(
      @RequestHeader(value = "x-webhook-signature", required = false) String signature,
      HttpServletRequest request) {
    byte[] rawBody = WebhookRawBodyFilter.rawBody(request);
    return ApiResponse.ok(cashfree.handleWebhook(signature, rawBody));
  }
}

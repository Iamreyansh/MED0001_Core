package com.nammamedmate.order.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.order.application.OrderPlacementService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Order payment")
public class OrderPaymentController {

  private final OrderPlacementService orders;

  public OrderPaymentController(OrderPlacementService orders) {
    this.orders = orders;
  }

  @PostMapping("/{orderId}/payment/confirm")
  @Operation(summary = "Confirm Razorpay payment (customer)")
  public ApiResponse<Map<String, Object>> confirm(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("orderId") UUID orderId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) ConfirmPaymentRequest body) {
    ConfirmPaymentRequest req = body == null ? new ConfirmPaymentRequest(null, null) : body;
    return ApiResponse.ok(
        orders.confirmPayment(
            principal, orderId, req.paymentId(), req.paymentSignature(), idempotencyKey));
  }

  @PostMapping("/{orderId}/payment/cod-collect")
  @Operation(summary = "Mark COD collected (rider)")
  public ApiResponse<Map<String, Object>> codCollect(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("orderId") UUID orderId,
      @RequestBody(required = false) CodCollectRequest body) {
    CodCollectRequest req = body == null ? new CodCollectRequest(null) : body;
    return ApiResponse.ok(orders.collectCod(principal, orderId, req.amountCollected()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ConfirmPaymentRequest(String paymentId, String paymentSignature) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CodCollectRequest(Object amountCollected) {}
}

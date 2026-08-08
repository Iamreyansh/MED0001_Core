package com.nammamedmate.order.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.order.application.OrderCancellationService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/orders")
@Tag(name = "Admin order cancel and refund")
public class AdminOrderCancelRefundController {

  private final OrderCancellationService cancellations;

  public AdminOrderCancelRefundController(OrderCancellationService cancellations) {
    this.cancellations = cancellations;
  }

  @PostMapping("/{orderId}/cancel")
  @Operation(summary = "Admin cancel order (not DELIVERED)")
  public ApiResponse<Map<String, Object>> cancel(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("orderId") UUID orderId,
      @RequestBody(required = false) AdminCancelRequest body) {
    AdminCancelRequest req = body == null ? new AdminCancelRequest(null, null, null) : body;
    return ApiResponse.ok(
        cancellations.adminCancel(
            principal, orderId, req.reason(), req.refundAmount(), req.refundTo()));
  }

  @PostMapping("/{orderId}/refund")
  @Operation(summary = "Admin issue (partial) refund")
  public ApiResponse<Map<String, Object>> refund(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("orderId") UUID orderId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) AdminRefundRequest body) {
    AdminRefundRequest req = body == null ? new AdminRefundRequest(null, null, null, null) : body;
    return ApiResponse.ok(
        cancellations.adminRefund(
            principal,
            orderId,
            req.amount(),
            req.refundTo(),
            req.reason(),
            req.notes(),
            idempotencyKey));
  }

  @GetMapping("/{orderId}/refund-eligibility")
  @Operation(summary = "Admin refund eligibility check")
  public ApiResponse<Map<String, Object>> eligibility(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("orderId") UUID orderId) {
    return ApiResponse.ok(cancellations.refundEligibility(principal, orderId));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AdminCancelRequest(String reason, Object refundAmount, String refundTo) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AdminRefundRequest(Object amount, String refundTo, String reason, String notes) {}
}

package com.nammamedmate.order.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.order.application.OrderCancellationService;
import com.nammamedmate.order.application.OrderLifecycleService;
import com.nammamedmate.order.application.OrderPlacementService;
import com.nammamedmate.order.application.ReorderService;
import com.nammamedmate.order.application.ReorderService.HistoryResult;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Customer orders")
public class OrderController {

  private final OrderPlacementService orders;
  private final OrderLifecycleService lifecycle;
  private final OrderCancellationService cancellations;
  private final ReorderService reorder;

  public OrderController(
      OrderPlacementService orders,
      OrderLifecycleService lifecycle,
      OrderCancellationService cancellations,
      ReorderService reorder) {
    this.orders = orders;
    this.lifecycle = lifecycle;
    this.cancellations = cancellations;
    this.reorder = reorder;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Place order from cart")
  public ApiResponse<Map<String, Object>> place(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) PlaceOrderRequest body) {
    PlaceOrderRequest req = body == null ? new PlaceOrderRequest(null, null, null, null) : body;
    return ApiResponse.ok(
        orders.placeOrder(
            principal,
            req.cartId(),
            req.paymentMethod(),
            req.paymentToken(),
            req.deliveryInstructions(),
            idempotencyKey));
  }

  @GetMapping("/history")
  @Operation(summary = "Order history (DELIVERED|CANCELLED)")
  public ApiResponse<List<Map<String, Object>>> history(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "status", required = false) String status) {
    HistoryResult result = reorder.history(principal, page, limit, status);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/active")
  @Operation(summary = "Active in-progress orders")
  public ApiResponse<List<Map<String, Object>>> active(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(reorder.active(principal));
  }

  @PostMapping("/{pastOrderId}/reorder")
  @Operation(summary = "Reorder from a past order into a new cart")
  public ApiResponse<Map<String, Object>> reorder(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("pastOrderId") UUID pastOrderId,
      @RequestBody(required = false) ReorderRequest body) {
    ReorderRequest req = body == null ? new ReorderRequest(null) : body;
    return ApiResponse.ok(reorder.reorder(principal, pastOrderId, req.confirmPharmacyChange()));
  }

  @GetMapping("/{orderId}")
  @Operation(summary = "Get order detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("orderId") UUID orderId) {
    return ApiResponse.ok(orders.getOrder(principal, orderId));
  }

  @GetMapping("/{orderId}/tracking")
  @Operation(summary = "Live order tracking (poll ~10s)")
  public ApiResponse<Map<String, Object>> tracking(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("orderId") UUID orderId) {
    return ApiResponse.ok(lifecycle.tracking(principal, orderId));
  }

  @GetMapping("/{orderId}/timeline")
  @Operation(summary = "Full order status timeline")
  public ApiResponse<Map<String, Object>> timeline(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("orderId") UUID orderId) {
    return ApiResponse.ok(lifecycle.timeline(principal, orderId));
  }

  @PostMapping("/{orderId}/cancel")
  @Operation(summary = "Customer cancel order (PENDING_ACCEPTANCE|ACCEPTED)")
  public ApiResponse<Map<String, Object>> cancel(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("orderId") UUID orderId,
      @RequestBody(required = false) CancelOrderRequest body) {
    CancelOrderRequest req = body == null ? new CancelOrderRequest(null) : body;
    return ApiResponse.ok(cancellations.customerCancel(principal, orderId, req.reason()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PlaceOrderRequest(
      UUID cartId, String paymentMethod, String paymentToken, String deliveryInstructions) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CancelOrderRequest(String reason) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReorderRequest(Boolean confirmPharmacyChange) {}
}

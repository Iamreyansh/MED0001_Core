package com.nammamedmate.order.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.order.application.OrderLifecycleService;
import com.nammamedmate.order.application.OrderLifecycleService.PharmacyInboxResult;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/orders")
@Tag(name = "Pharmacy order lifecycle")
public class PharmacyOrderLifecycleController {

  private final OrderLifecycleService lifecycle;

  public PharmacyOrderLifecycleController(OrderLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  @GetMapping
  @Operation(summary = "List inbound orders for the active pharmacy")
  public ApiResponse<List<Map<String, Object>>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "limit", required = false) Integer limit) {
    PharmacyInboxResult result = lifecycle.listPharmacyInbox(principal, status, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/{orderId}")
  @Operation(summary = "Get one inbound order scoped to the active pharmacy")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("orderId") UUID orderId) {
    return ApiResponse.ok(lifecycle.getPharmacyOrder(principal, orderId));
  }

  @GetMapping("/{orderId}/handoff")
  @Operation(summary = "Pickup OTP for pharmacy-to-rider handoff (never customer delivery OTP)")
  public ApiResponse<Map<String, Object>> handoff(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("orderId") UUID orderId) {
    return ApiResponse.ok(lifecycle.pharmacyHandoff(principal, orderId));
  }

  @PostMapping("/{orderId}/accept")
  @Operation(summary = "Accept order within 10-minute window")
  public ApiResponse<Map<String, Object>> accept(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("orderId") UUID orderId) {
    return ApiResponse.ok(lifecycle.accept(principal, orderId));
  }

  @PatchMapping("/{orderId}/status")
  @Operation(summary = "Advance packing status (ACCEPTED→PACKING→READY_FOR_PICKUP)")
  public ApiResponse<Map<String, Object>> advance(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("orderId") UUID orderId,
      @RequestBody(required = false) StatusRequest body) {
    StatusRequest req = body == null ? new StatusRequest(null, null) : body;
    return ApiResponse.ok(
        lifecycle.advancePharmacyStatus(principal, orderId, req.status(), req.notes()));
  }

  @PostMapping("/{orderId}/reject")
  @Operation(summary = "Reject order and trigger refund skeleton")
  public ApiResponse<Map<String, Object>> reject(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("orderId") UUID orderId,
      @RequestBody(required = false) RejectRequest body) {
    RejectRequest req = body == null ? new RejectRequest(null, null) : body;
    return ApiResponse.ok(lifecycle.reject(principal, orderId, req.reason(), req.message()));
  }

  @PostMapping("/{orderId}/assign-rider")
  @Operation(summary = "Assign rider for pharmacy dispatch")
  public ApiResponse<Map<String, Object>> assignRider(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("orderId") UUID orderId,
      @RequestBody(required = false) AssignRiderRequest body) {
    AssignRiderRequest req = body == null ? new AssignRiderRequest(null) : body;
    return ApiResponse.ok(lifecycle.assignRider(principal, orderId, req.riderId()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record StatusRequest(String status, String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RejectRequest(String reason, String message) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AssignRiderRequest(UUID riderId) {}
}

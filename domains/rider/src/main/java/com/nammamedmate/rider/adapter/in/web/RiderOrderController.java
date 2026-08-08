package com.nammamedmate.rider.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.rider.application.RiderOrderService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rider/orders")
@Tag(name = "Rider orders")
public class RiderOrderController {

  private final RiderOrderService service;

  public RiderOrderController(RiderOrderService service) {
    this.service = service;
  }

  @GetMapping("/current")
  @Operation(summary = "Rider: current active order")
  public ApiResponse<Map<String, Object>> current(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.current(principal));
  }

  @PostMapping("/{order_id}/accept")
  @Operation(summary = "Rider: accept assigned order")
  public ApiResponse<Map<String, Object>> accept(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("order_id") UUID orderId) {
    return ApiResponse.ok(service.accept(principal, orderId));
  }

  @PostMapping("/{order_id}/pickup-confirm")
  @Operation(summary = "Rider: confirm pharmacy pickup with OTP")
  public ApiResponse<Map<String, Object>> pickupConfirm(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("order_id") UUID orderId,
      @RequestBody(required = false) PickupRequest body) {
    return ApiResponse.ok(
        service.pickupConfirm(principal, orderId, body == null ? null : body.pickupOtp()));
  }

  @PostMapping("/{order_id}/deliver")
  @Operation(summary = "Rider: confirm delivery with customer OTP")
  public ApiResponse<Map<String, Object>> deliver(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("order_id") UUID orderId,
      @RequestBody(required = false) DeliverRequest body) {
    return ApiResponse.ok(
        service.deliver(principal, orderId, body == null ? null : body.deliveryOtpFromCustomer()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PickupRequest(String pickupOtp) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DeliverRequest(String deliveryOtpFromCustomer) {}
}

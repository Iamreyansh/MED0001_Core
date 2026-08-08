package com.nammamedmate.rider.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.rider.application.RiderLocationService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Customer rider tracking")
public class CustomerRiderLocationController {

  private final RiderLocationService service;

  public CustomerRiderLocationController(RiderLocationService service) {
    this.service = service;
  }

  @GetMapping("/{order_id}/rider-location")
  @Operation(summary = "Live rider location + ETA for OUT_FOR_DELIVERY order")
  public ApiResponse<Map<String, Object>> riderLocation(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("order_id") UUID orderId) {
    return ApiResponse.ok(service.customerRiderLocation(principal, orderId));
  }

  @GetMapping(
      path = "/{order_id}/rider-location/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(summary = "SSE stream of rider location + ETA (STORY-004 WS fallback)")
  public SseEmitter stream(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("order_id") UUID orderId) {
    return service.subscribeCustomerStream(principal, orderId);
  }
}

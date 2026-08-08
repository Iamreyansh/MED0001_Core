package com.nammamedmate.order.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.order.application.OrderLifecycleService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/orders")
@Tag(name = "Admin order lifecycle")
public class AdminOrderLifecycleController {

  private final OrderLifecycleService lifecycle;

  public AdminOrderLifecycleController(OrderLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  @PatchMapping("/{orderId}/status")
  @Operation(summary = "Force-advance order status from non-terminal state")
  public ApiResponse<Map<String, Object>> forceStatus(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("orderId") UUID orderId,
      @RequestBody(required = false) ForceStatusRequest body) {
    ForceStatusRequest req = body == null ? new ForceStatusRequest(null, null, null) : body;
    return ApiResponse.ok(
        lifecycle.adminForceStatus(principal, orderId, req.status(), req.reason(), req.notes()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ForceStatusRequest(String status, String reason, String notes) {}
}

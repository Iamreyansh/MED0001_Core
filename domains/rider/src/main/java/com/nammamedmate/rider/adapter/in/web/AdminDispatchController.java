package com.nammamedmate.rider.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.rider.application.DispatchService;
import com.nammamedmate.rider.application.DispatchService.QueueResult;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/dispatch")
@Tag(name = "Admin dispatch")
public class AdminDispatchController {

  private final DispatchService service;

  public AdminDispatchController(DispatchService service) {
    this.service = service;
  }

  @GetMapping("/queue")
  @RequiresPermission("orders:dispatch")
  @Operation(summary = "Admin: unassigned READY_FOR_PICKUP dispatch queue")
  public ApiResponse<Map<String, Object>> queue(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "zone_id", required = false) UUID zoneId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    QueueResult result = service.queue(principal, zoneId, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/orders/{order_id}/assign")
  @RequiresPermission("orders:dispatch")
  @Operation(summary = "Admin: manually assign rider to order")
  public ApiResponse<Map<String, Object>> assign(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("order_id") UUID orderId,
      @RequestBody(required = false) AssignRequest body) {
    UUID riderId = body == null ? null : body.riderId();
    return ApiResponse.ok(service.assignManual(principal, orderId, riderId));
  }

  @PostMapping("/auto-assign-all")
  @RequiresPermission("orders:dispatch")
  @Operation(summary = "Admin: auto-assign all unassigned queue orders")
  public ApiResponse<Map<String, Object>> autoAssignAll(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.autoAssignAll(principal));
  }

  @PostMapping("/orders/{order_id}/reassign")
  @RequiresPermission("orders:dispatch")
  @Operation(summary = "Admin: reassign order to another rider")
  public ApiResponse<Map<String, Object>> reassign(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("order_id") UUID orderId,
      @RequestBody(required = false) ReassignRequest body) {
    if (body == null) {
      throw new AppException("REASON_REQUIRED", "reason field missing", 422);
    }
    return ApiResponse.ok(service.reassign(principal, orderId, body.riderId(), body.reason()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AssignRequest(UUID riderId) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReassignRequest(UUID riderId, String reason) {}
}

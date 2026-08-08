package com.nammamedmate.rider.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.rider.application.AdminRiderService;
import com.nammamedmate.rider.application.AdminRiderService.ListResult;
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
@RequestMapping("/api/v1/admin/riders")
@Tag(name = "Admin riders")
public class AdminRiderController {

  private final AdminRiderService service;

  public AdminRiderController(AdminRiderService service) {
    this.service = service;
  }

  @GetMapping
  @RequiresPermission("riders:read")
  @Operation(summary = "Admin: list riders / KYC queue")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String order,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    ListResult result = service.list(principal, status, sort, order, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/{id}/approve")
  @RequiresPermission("riders:assign")
  @Operation(summary = "Admin: approve rider KYC")
  public ApiResponse<Map<String, Object>> approve(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) ApproveRequest body) {
    return ApiResponse.ok(service.approve(principal, id, body == null ? null : body.notes()));
  }

  @PostMapping("/{id}/reject")
  @RequiresPermission("riders:assign")
  @Operation(summary = "Admin: reject rider KYC")
  public ApiResponse<Map<String, Object>> reject(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody RejectRequest body) {
    return ApiResponse.ok(
        service.reject(
            principal,
            id,
            body == null ? null : body.reason(),
            body == null ? null : body.notes()));
  }

  @PostMapping("/{id}/block")
  @RequiresPermission("riders:assign")
  @Operation(summary = "Admin: block rider")
  public ApiResponse<Map<String, Object>> block(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody BlockRequest body) {
    return ApiResponse.ok(
        service.block(
            principal,
            id,
            body == null ? null : body.reason(),
            body == null ? null : body.notes()));
  }

  @PostMapping("/{id}/unblock")
  @RequiresPermission("riders:assign")
  @Operation(summary = "Admin: unblock rider")
  public ApiResponse<Map<String, Object>> unblock(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) UnblockRequest body) {
    return ApiResponse.ok(service.unblock(principal, id, body == null ? null : body.notes()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ApproveRequest(String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RejectRequest(String reason, String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record BlockRequest(String reason, String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UnblockRequest(String notes) {}
}

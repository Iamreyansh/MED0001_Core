package com.nammamedmate.rider.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.rider.application.AdminFleetService;
import com.nammamedmate.rider.application.AdminFleetService.FleetResult;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin rider fleet")
public class AdminFleetController {

  private final AdminFleetService service;

  public AdminFleetController(AdminFleetService service) {
    this.service = service;
  }

  @GetMapping("/riders/fleet")
  @RequiresPermission("riders:read")
  @Operation(summary = "Admin: live fleet overview")
  public ApiResponse<Map<String, Object>> fleet(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "zone_id", required = false) UUID zoneId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    FleetResult result = service.fleetOverview(principal, zoneId, status, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/zones/{zone_id}/riders")
  @RequiresPermission("riders:read")
  @Operation(summary = "Admin: riders in zone with coverage")
  public ApiResponse<Map<String, Object>> zoneRiders(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("zone_id") UUID zoneId) {
    return ApiResponse.ok(service.zoneRiders(principal, zoneId));
  }

  @PatchMapping("/riders/{id}/status")
  @RequiresPermission("riders:assign")
  @Operation(summary = "Admin: force-change rider status")
  public ApiResponse<Map<String, Object>> forceStatus(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody ForceStatusRequest body) {
    return ApiResponse.ok(
        service.forceStatus(
            principal,
            id,
            body == null ? null : body.status(),
            body == null ? null : body.reason()));
  }

  @PatchMapping("/riders/{id}/zone")
  @RequiresPermission("riders:assign")
  @Operation(summary = "Admin: reassign rider zone")
  public ApiResponse<Map<String, Object>> reassignZone(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody ZoneRequest body) {
    if (body == null || body.zoneId() == null) {
      throw new AppException("INVALID_ZONE", "zone_id does not exist", 422);
    }
    return ApiResponse.ok(service.reassignZone(principal, id, body.zoneId(), body.notifyRider()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ForceStatusRequest(String status, String reason) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ZoneRequest(UUID zoneId, Boolean notifyRider) {}
}

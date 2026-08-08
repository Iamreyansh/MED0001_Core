package com.nammamedmate.rider.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.rider.application.RiderLocationService;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin rider GPS")
public class AdminRiderLocationController {

  private final RiderLocationService service;

  public AdminRiderLocationController(RiderLocationService service) {
    this.service = service;
  }

  @GetMapping("/riders/{id}/location")
  @RequiresPermission("riders:read")
  @Operation(summary = "Admin: live rider GPS")
  public ApiResponse<Map<String, Object>> location(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.adminLiveLocation(principal, id));
  }

  @GetMapping("/riders/{id}/location-history")
  @RequiresPermission("riders:read")
  @Operation(summary = "Admin: GPS trail for an order")
  public ApiResponse<Map<String, Object>> history(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestParam(name = "order_id", required = false) UUID orderId) {
    return ApiResponse.ok(service.adminLocationHistory(principal, id, orderId));
  }

  @PostMapping("/geofences")
  @RequiresPermission("riders:assign")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Admin: create zone geofence polygon")
  public ApiResponse<Map<String, Object>> createGeofence(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody GeofenceRequest body) {
    return ApiResponse.ok(
        service.createGeofence(
            principal,
            body == null ? null : body.zoneId(),
            body == null ? null : body.polygonCoordinates()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record GeofenceRequest(UUID zoneId, List<List<Double>> polygonCoordinates) {
    public GeofenceRequest {
      if (polygonCoordinates != null) {
        polygonCoordinates =
            List.copyOf(
                polygonCoordinates.stream()
                    .map(c -> c == null ? List.<Double>of() : List.copyOf(c))
                    .toList());
      }
    }
  }
}

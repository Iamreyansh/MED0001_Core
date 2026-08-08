package com.nammamedmate.rider.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.rider.application.RiderStatusService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rider/status")
@Tag(name = "Rider status")
public class RiderStatusController {

  private final RiderStatusService service;

  public RiderStatusController(RiderStatusService service) {
    this.service = service;
  }

  @PostMapping
  @Operation(summary = "Set rider ONLINE/OFFLINE availability")
  public ApiResponse<Map<String, Object>> setStatus(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody StatusRequest body) {
    return ApiResponse.ok(
        service.setStatus(
            principal, body == null ? null : body.status(), body == null ? null : body.zoneId()));
  }

  @GetMapping
  @Operation(summary = "Get rider current status and shift summary")
  public ApiResponse<Map<String, Object>> getStatus(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.getStatus(principal));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record StatusRequest(String status, UUID zoneId) {}
}

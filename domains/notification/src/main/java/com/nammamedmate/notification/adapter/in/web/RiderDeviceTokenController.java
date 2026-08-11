package com.nammamedmate.notification.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.notification.application.DeviceTokenService;
import com.nammamedmate.notification.domain.NotificationUserType;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rider/me/device-token")
@Tag(name = "Rider device tokens")
public class RiderDeviceTokenController {

  private final DeviceTokenService tokens;

  public RiderDeviceTokenController(DeviceTokenService tokens) {
    this.tokens = tokens;
  }

  @PostMapping
  @Operation(summary = "Register or refresh FCM device token (rider)")
  public ApiResponse<Map<String, Object>> register(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) RegisterRequest body) {
    RegisterRequest req = body == null ? new RegisterRequest(null, null, null) : body;
    return ApiResponse.ok(
        tokens.register(
            principal.subject(),
            NotificationUserType.RIDER,
            req.token(),
            req.platform(),
            req.deviceId()));
  }

  @DeleteMapping
  @Operation(summary = "Unregister FCM device token (rider)")
  public ApiResponse<Map<String, Object>> unregister(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) UnregisterRequest body) {
    UnregisterRequest req = body == null ? new UnregisterRequest(null) : body;
    return ApiResponse.ok(
        tokens.unregister(principal.subject(), NotificationUserType.RIDER, req.deviceId()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RegisterRequest(String token, String platform, String deviceId) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UnregisterRequest(String deviceId) {}
}

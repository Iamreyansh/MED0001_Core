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
@RequestMapping("/api/v1/pharmacy/me/device-token")
@Tag(name = "Pharmacy device tokens")
public class PharmacyDeviceTokenController {

  private final DeviceTokenService tokens;

  public PharmacyDeviceTokenController(DeviceTokenService tokens) {
    this.tokens = tokens;
  }

  @PostMapping
  @Operation(summary = "Register or refresh FCM device token (pharmacy staff)")
  public ApiResponse<Map<String, Object>> register(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) RegisterRequest body) {
    RegisterRequest req = body == null ? new RegisterRequest(null, null, null) : body;
    return ApiResponse.ok(
        tokens.register(
            principal.subject(),
            NotificationUserType.PHARMACY_STAFF,
            req.token(),
            req.platform(),
            req.deviceId()));
  }

  @DeleteMapping
  @Operation(summary = "Unregister FCM device token (pharmacy staff)")
  public ApiResponse<Map<String, Object>> unregister(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) UnregisterRequest body) {
    UnregisterRequest req = body == null ? new UnregisterRequest(null) : body;
    return ApiResponse.ok(
        tokens.unregister(
            principal.subject(), NotificationUserType.PHARMACY_STAFF, req.deviceId()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RegisterRequest(String token, String platform, String deviceId) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UnregisterRequest(String deviceId) {}
}

package com.nammamedmate.notification.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.notification.application.PreferenceService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers/me/notification-preferences")
@Tag(name = "Customer notification preferences")
public class CustomerNotificationPreferencesController {

  private final PreferenceService preferences;

  public CustomerNotificationPreferencesController(PreferenceService preferences) {
    this.preferences = preferences;
  }

  @GetMapping
  @Operation(summary = "Get notification preferences for authenticated customer")
  public ApiResponse<Map<String, Object>> get(@AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(preferences.getCustomerPreferences(principal.subject()));
  }

  @PatchMapping
  @Operation(summary = "Update notification preferences for authenticated customer")
  public ApiResponse<Map<String, Object>> patch(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) PatchRequest body) {
    PatchRequest req = body == null ? new PatchRequest(null, null) : body;
    return ApiResponse.ok(
        preferences.patchCustomerPreferences(
            principal.subject(), req.channels(), req.categories()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PatchRequest(Map<String, Boolean> channels, Map<String, Boolean> categories) {
    public PatchRequest {
      channels =
          channels == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(channels));
      categories =
          categories == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(categories));
    }
  }
}

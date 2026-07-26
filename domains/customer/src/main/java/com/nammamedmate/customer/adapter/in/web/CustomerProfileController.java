package com.nammamedmate.customer.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.customer.application.CustomerProfileService;
import com.nammamedmate.customer.application.CustomerProfileService.UpdateProfileCommand;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customer profile")
public class CustomerProfileController {

  private final CustomerProfileService service;

  public CustomerProfileController(CustomerProfileService service) {
    this.service = service;
  }

  @GetMapping("/me")
  @Operation(summary = "Get own customer profile")
  public ApiResponse<Map<String, Object>> getMe(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.getMe(principal));
  }

  @PatchMapping("/me")
  @Operation(summary = "Update own customer profile")
  public ApiResponse<Map<String, Object>> updateMe(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) UpdateProfileRequest body) {
    UpdateProfileCommand cmd =
        body == null
            ? new UpdateProfileCommand(null, null, null, null, null)
            : new UpdateProfileCommand(
                body.name(),
                body.avatarUrl(),
                body.dateOfBirth(),
                body.gender(),
                body.preferredLanguage());
    return ApiResponse.ok(service.updateMe(principal, cmd));
  }

  @DeleteMapping("/me")
  @Operation(summary = "Request account deletion (30-day grace)")
  public ApiResponse<Map<String, Object>> deleteMe(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) DeleteAccountRequest body) {
    return ApiResponse.ok(service.requestDeletion(principal, body == null ? null : body.reason()));
  }

  @PostMapping("/me/cancel-deletion")
  @Operation(summary = "Cancel pending account deletion")
  public ApiResponse<Map<String, Object>> cancelDeletion(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.cancelDeletion(principal));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UpdateProfileRequest(
      String name, String avatarUrl, String dateOfBirth, String gender, String preferredLanguage) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DeleteAccountRequest(String reason) {}
}

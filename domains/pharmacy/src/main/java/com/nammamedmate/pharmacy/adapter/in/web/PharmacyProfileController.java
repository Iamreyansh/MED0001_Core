package com.nammamedmate.pharmacy.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pharmacy.application.PharmacyProfileService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/profile")
@Tag(name = "Pharmacy profile")
public class PharmacyProfileController {

  private final PharmacyProfileService service;

  public PharmacyProfileController(PharmacyProfileService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "Get pharmacy profile")
  public ApiResponse<Map<String, Object>> getProfile(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.getProfile(principal));
  }

  @PatchMapping
  @Operation(summary = "Update pharmacy profile (owner)")
  public ApiResponse<Map<String, Object>> patchProfile(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(service.patchProfile(principal, body == null ? Map.of() : body));
  }

  @PatchMapping("/tax")
  @Operation(summary = "Update tax and compliance details")
  public ApiResponse<Map<String, Object>> patchTax(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody Map<String, Object> body) {
    return ApiResponse.ok(service.patchTax(principal, body == null ? Map.of() : body));
  }

  @GetMapping("/completeness")
  @Operation(summary = "Get profile completeness score")
  public ApiResponse<Map<String, Object>> completeness(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.getCompleteness(principal));
  }

  @PostMapping("/bank-account")
  @Operation(summary = "Save bank account and initiate penny drop")
  public ResponseEntity<ApiResponse<Map<String, Object>>> saveBankAccount(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody Map<String, Object> body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(service.saveBankAccount(principal, body == null ? Map.of() : body)));
  }

  @GetMapping("/bank-account")
  @Operation(summary = "Get bank account details")
  public ApiResponse<Map<String, Object>> getBankAccount(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.getBankAccount(principal));
  }

  @PostMapping("/verify-contact")
  @Operation(summary = "Verify pending phone or email change with OTP")
  public ApiResponse<Map<String, Object>> verifyContact(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody VerifyContactRequest body) {
    VerifyContactRequest req = body == null ? new VerifyContactRequest(null, null) : body;
    return ApiResponse.ok(service.verifyContact(principal, req.channel(), req.otp()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record VerifyContactRequest(String channel, String otp) {}
}

package com.nammamedmate.pharmacy.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pharmacy.application.PharmacyRegistrationService;
import com.nammamedmate.pharmacy.application.PharmacyRegistrationService.AddressCommand;
import com.nammamedmate.pharmacy.application.PharmacyRegistrationService.RegisterCommand;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy")
@Tag(name = "Pharmacy registration")
public class PharmacyRegistrationController {

  private final PharmacyRegistrationService service;

  public PharmacyRegistrationController(PharmacyRegistrationService service) {
    this.service = service;
  }

  @PostMapping("/register")
  @Operation(summary = "Self-service pharmacy registration")
  public ResponseEntity<ApiResponse<Map<String, Object>>> register(
      @RequestBody(required = false) RegisterRequest body, HttpServletRequest request) {
    RegisterCommand cmd =
        body == null
            ? null
            : new RegisterCommand(
                body.ownerName(),
                body.businessName(),
                body.phone(),
                body.email(),
                body.password(),
                body.businessType(),
                body.address() == null
                    ? null
                    : new AddressCommand(
                        body.address().flat(),
                        body.address().area(),
                        body.address().city(),
                        body.address().state(),
                        body.address().pincode(),
                        body.address().latitude(),
                        body.address().longitude()),
                body.gstin(),
                body.drugLicenceNumber(),
                body.fssaiNumber(),
                body.panNumber(),
                body.address() == null ? null : body.address().pincode());
    Map<String, Object> data = service.register(cmd, clientIp(request));
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @PostMapping("/register/verify-email")
  @Operation(summary = "Verify pharmacy registration email OTP")
  public ApiResponse<Map<String, Object>> verifyEmail(
      @RequestBody(required = false) VerifyEmailRequest body, HttpServletRequest request) {
    return ApiResponse.ok(
        service.verifyEmail(
            body == null ? null : body.email(),
            body == null ? null : body.otp(),
            clientIp(request),
            request.getHeader("User-Agent")));
  }

  @PostMapping("/register/resend-otp")
  @Operation(summary = "Resend pharmacy registration email OTP")
  public ApiResponse<Map<String, Object>> resendOtp(
      @RequestBody(required = false) ResendOtpRequest body, HttpServletRequest request) {
    return ApiResponse.ok(service.resendOtp(body == null ? null : body.email(), clientIp(request)));
  }

  @GetMapping("/registration-status")
  @Operation(summary = "Get pharmacy registration and KYC status")
  public ApiResponse<Map<String, Object>> registrationStatus(
      @AuthenticationPrincipal MedmatePrincipal principal, HttpServletRequest request) {
    return ApiResponse.ok(service.registrationStatus(principal, clientIp(request)));
  }

  /** Uses remote address only — X-Forwarded-For is client-spoofable without a trusted proxy. */
  private static String clientIp(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    return remote == null || remote.isBlank() ? "0.0.0.0" : remote;
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RegisterRequest(
      String ownerName,
      String businessName,
      String phone,
      String email,
      String password,
      String businessType,
      AddressRequest address,
      String gstin,
      String drugLicenceNumber,
      String fssaiNumber,
      String panNumber) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AddressRequest(
      String flat,
      String area,
      String city,
      String state,
      String pincode,
      Double latitude,
      Double longitude) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record VerifyEmailRequest(String email, String otp) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ResendOtpRequest(String email) {}
}

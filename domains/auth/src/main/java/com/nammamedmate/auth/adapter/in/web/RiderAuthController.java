package com.nammamedmate.auth.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.auth.adapter.in.web.dto.SendOtpRequest;
import com.nammamedmate.auth.adapter.in.web.dto.SendOtpResponse;
import com.nammamedmate.auth.adapter.in.web.dto.VerifyOtpRequest;
import com.nammamedmate.auth.application.SendOtpCommand;
import com.nammamedmate.auth.application.SendOtpResult;
import com.nammamedmate.auth.application.SendOtpService;
import com.nammamedmate.auth.application.VerifyOtpCommand;
import com.nammamedmate.auth.application.VerifyRiderOtpResult;
import com.nammamedmate.auth.application.VerifyRiderOtpService;
import com.nammamedmate.auth.application.port.out.RiderAccountPort.RiderAccount;
import com.nammamedmate.kernel.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/rider")
public class RiderAuthController {

  private final SendOtpService sendOtpService;
  private final VerifyRiderOtpService verifyRiderOtpService;
  private final ObjectMapper objectMapper;

  public RiderAuthController(
      SendOtpService sendOtpService,
      VerifyRiderOtpService verifyRiderOtpService,
      ObjectMapper objectMapper) {
    this.sendOtpService = sendOtpService;
    this.verifyRiderOtpService = verifyRiderOtpService;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/send-otp")
  public ApiResponse<SendOtpResponse> sendOtp(
      @Valid @RequestBody SendOtpRequest request, HttpServletRequest httpRequest) {
    SendOtpResult result =
        sendOtpService.send(
            new SendOtpCommand(request.phone(), deviceInfoJson(request), clientIp(httpRequest)));
    return ApiResponse.ok(
        new SendOtpResponse(
            result.sessionId(),
            result.phone(),
            result.expiresAt(),
            result.resendAllowedAt(),
            result.attemptsRemaining()));
  }

  @PostMapping("/verify-otp")
  public ApiResponse<VerifyRiderOtpResponse> verifyOtp(
      @Valid @RequestBody VerifyOtpRequest request, HttpServletRequest httpRequest) {
    VerifyRiderOtpResult result =
        verifyRiderOtpService.verify(
            new VerifyOtpCommand(
                request.sessionId(),
                request.phone(),
                request.otp(),
                request.deviceToken(),
                clientIp(httpRequest),
                httpRequest.getHeader("User-Agent")));
    return ApiResponse.ok(VerifyRiderOtpResponse.from(result));
  }

  private String deviceInfoJson(SendOtpRequest request) {
    if (request.deviceInfo() == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(request.deviceInfo());
    } catch (JsonProcessingException ex) {
      return null;
    }
  }

  private static String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    String remote = request.getRemoteAddr();
    return remote == null || remote.isBlank() ? "0.0.0.0" : remote;
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record VerifyRiderOtpResponse(
      String accessToken,
      String refreshToken,
      String tokenType,
      long accessTokenExpiresIn,
      long refreshTokenExpiresIn,
      RiderResponse rider) {

    static VerifyRiderOtpResponse from(VerifyRiderOtpResult result) {
      return new VerifyRiderOtpResponse(
          result.accessToken(),
          result.refreshToken(),
          result.tokenType(),
          result.accessTokenExpiresIn(),
          result.refreshTokenExpiresIn(),
          RiderResponse.from(result.rider()));
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RiderResponse(
      UUID id, String phone, String name, String status, String kycStatus, String email) {

    static RiderResponse from(RiderAccount rider) {
      return new RiderResponse(
          rider.id(),
          rider.phone(),
          rider.name(),
          rider.status(),
          rider.kycStatus(),
          rider.email());
    }
  }
}

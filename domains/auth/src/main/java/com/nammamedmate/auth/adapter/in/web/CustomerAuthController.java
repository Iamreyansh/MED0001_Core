package com.nammamedmate.auth.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.auth.adapter.in.web.dto.SendOtpRequest;
import com.nammamedmate.auth.adapter.in.web.dto.SendOtpResponse;
import com.nammamedmate.auth.adapter.in.web.dto.VerifyOtpRequest;
import com.nammamedmate.auth.adapter.in.web.dto.VerifyOtpResponse;
import com.nammamedmate.auth.application.SendOtpCommand;
import com.nammamedmate.auth.application.SendOtpResult;
import com.nammamedmate.auth.application.SendOtpService;
import com.nammamedmate.auth.application.VerifyOtpCommand;
import com.nammamedmate.auth.application.VerifyOtpResult;
import com.nammamedmate.auth.application.VerifyOtpService;
import com.nammamedmate.kernel.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/customer")
public class CustomerAuthController {

  private final SendOtpService sendOtpService;
  private final VerifyOtpService verifyOtpService;
  private final ObjectMapper objectMapper;

  public CustomerAuthController(
      SendOtpService sendOtpService, VerifyOtpService verifyOtpService, ObjectMapper objectMapper) {
    this.sendOtpService = sendOtpService;
    this.verifyOtpService = verifyOtpService;
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
  public ApiResponse<VerifyOtpResponse> verifyOtp(
      @Valid @RequestBody VerifyOtpRequest request, HttpServletRequest httpRequest) {
    VerifyOtpResult result =
        verifyOtpService.verify(
            new VerifyOtpCommand(
                request.sessionId(),
                request.phone(),
                request.otp(),
                request.deviceToken(),
                clientIp(httpRequest),
                httpRequest.getHeader("User-Agent")));
    return ApiResponse.ok(VerifyOtpResponse.from(result));
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
}

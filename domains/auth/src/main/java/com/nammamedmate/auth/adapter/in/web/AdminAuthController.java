package com.nammamedmate.auth.adapter.in.web;

import com.nammamedmate.auth.adapter.in.web.dto.AdminLoginRequest;
import com.nammamedmate.auth.adapter.in.web.dto.AdminLoginResponse;
import com.nammamedmate.auth.adapter.in.web.dto.AdminSetupMfaResponse;
import com.nammamedmate.auth.adapter.in.web.dto.AdminVerifyMfaRequest;
import com.nammamedmate.auth.adapter.in.web.dto.AdminVerifyMfaResponse;
import com.nammamedmate.auth.application.AdminLoginService;
import com.nammamedmate.auth.application.AdminSetupMfaService;
import com.nammamedmate.auth.application.AdminVerifyMfaService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/admin")
public class AdminAuthController {

  private final AdminLoginService loginService;
  private final AdminVerifyMfaService verifyMfaService;
  private final AdminSetupMfaService setupMfaService;
  private final com.nammamedmate.auth.application.AdminInviteCompleteService inviteCompleteService;

  public AdminAuthController(
      AdminLoginService loginService,
      AdminVerifyMfaService verifyMfaService,
      AdminSetupMfaService setupMfaService,
      com.nammamedmate.auth.application.AdminInviteCompleteService inviteCompleteService) {
    this.loginService = loginService;
    this.verifyMfaService = verifyMfaService;
    this.setupMfaService = setupMfaService;
    this.inviteCompleteService = inviteCompleteService;
  }

  @PostMapping("/complete-invite")
  public ApiResponse<Map<String, Object>> completeInvite(
      @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> req = body == null ? Map.of() : body;
    Object token = req.get("invite_token");
    Object password = req.get("password");
    return ApiResponse.ok(
        inviteCompleteService.complete(
            token == null ? null : String.valueOf(token),
            password == null ? null : String.valueOf(password)));
  }

  @PostMapping("/login")
  public ApiResponse<AdminLoginResponse> login(
      @Valid @RequestBody AdminLoginRequest request, HttpServletRequest http) {
    var result =
        loginService.login(
            request.email(), request.password(), clientIp(http), http.getHeader("User-Agent"));
    return ApiResponse.ok(AdminLoginResponse.from(result));
  }

  @PostMapping("/verify-mfa")
  public ApiResponse<AdminVerifyMfaResponse> verifyMfa(
      @Valid @RequestBody AdminVerifyMfaRequest request,
      @AuthenticationPrincipal MedmatePrincipal principal,
      HttpServletRequest http) {
    if (principal == null || principal.tokenScope() != TokenScope.MFA_CHALLENGE) {
      throw new AppException("CHALLENGE_TOKEN_INVALID", "Not a valid MFA challenge token", 401);
    }
    String bearer = bearerToken(http);
    if (bearer == null || !bearer.equals(request.mfaChallengeToken())) {
      throw new AppException(
          "CHALLENGE_TOKEN_INVALID", "Challenge token must match Authorization Bearer", 401);
    }
    var result =
        verifyMfaService.verify(
            request.mfaChallengeToken(),
            request.code(),
            clientIp(http),
            http.getHeader("User-Agent"));
    return ApiResponse.ok(AdminVerifyMfaResponse.from(result));
  }

  @PostMapping("/setup-mfa")
  public ApiResponse<AdminSetupMfaResponse> setupMfa(
      @RequestBody(required = false) Map<String, Object> ignored,
      @AuthenticationPrincipal MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.tokenScope() == TokenScope.POS
        || principal.tokenScope() == TokenScope.MFA_CHALLENGE) {
      throw new AppException("FORBIDDEN", "Full admin session required", 403);
    }
    var result = setupMfaService.setup(principal.subject());
    return ApiResponse.ok(AdminSetupMfaResponse.from(result));
  }

  /** Prefer first X-Forwarded-For hop (ALB); fall back to remote address. */
  private static String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    String remote = request.getRemoteAddr();
    return remote == null || remote.isBlank() ? "0.0.0.0" : remote;
  }

  private static String bearerToken(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith("Bearer ")) {
      return null;
    }
    String token = header.substring(7).trim();
    return token.isEmpty() ? null : token;
  }
}

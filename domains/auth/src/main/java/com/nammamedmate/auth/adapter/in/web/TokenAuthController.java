package com.nammamedmate.auth.adapter.in.web;

import com.nammamedmate.auth.adapter.in.web.dto.LogoutAllResponse;
import com.nammamedmate.auth.adapter.in.web.dto.LogoutRequest;
import com.nammamedmate.auth.adapter.in.web.dto.LogoutResponse;
import com.nammamedmate.auth.adapter.in.web.dto.RefreshTokenRequest;
import com.nammamedmate.auth.adapter.in.web.dto.RevokeSessionResponse;
import com.nammamedmate.auth.adapter.in.web.dto.TokenPairResponse;
import com.nammamedmate.auth.application.CurrentUserService;
import com.nammamedmate.auth.application.RefreshTokenService;
import com.nammamedmate.auth.application.SessionListService;
import com.nammamedmate.auth.application.SessionLogoutService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.MedmatePrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class TokenAuthController {

  private final RefreshTokenService refreshTokenService;
  private final SessionLogoutService logoutService;
  private final CurrentUserService currentUserService;
  private final SessionListService sessionListService;

  public TokenAuthController(
      RefreshTokenService refreshTokenService,
      SessionLogoutService logoutService,
      CurrentUserService currentUserService,
      SessionListService sessionListService) {
    this.refreshTokenService = refreshTokenService;
    this.logoutService = logoutService;
    this.currentUserService = currentUserService;
    this.sessionListService = sessionListService;
  }

  @PostMapping("/refresh")
  public ApiResponse<TokenPairResponse> refresh(
      @Valid @RequestBody RefreshTokenRequest request, HttpServletRequest http) {
    return ApiResponse.ok(
        TokenPairResponse.from(
            refreshTokenService.refresh(request.refreshToken(), clientIp(http))));
  }

  @PostMapping("/logout")
  public ApiResponse<LogoutResponse> logout(
      @Valid @RequestBody LogoutRequest request,
      @AuthenticationPrincipal MedmatePrincipal principal) {
    requireAuth(principal);
    logoutService.logout(principal, request.refreshToken());
    return ApiResponse.ok(LogoutResponse.ok());
  }

  @PostMapping("/logout-all")
  public ApiResponse<LogoutAllResponse> logoutAll(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    requireAuth(principal);
    int revoked = logoutService.logoutAll(principal);
    return ApiResponse.ok(LogoutAllResponse.of(revoked));
  }

  @GetMapping("/me")
  public ApiResponse<Map<String, Object>> me(@AuthenticationPrincipal MedmatePrincipal principal) {
    requireAuth(principal);
    return ApiResponse.ok(currentUserService.me(principal));
  }

  @GetMapping("/sessions")
  public ApiResponse<List<Map<String, Object>>> sessions(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    requireAuth(principal);
    SessionListService.SessionListResult result = sessionListService.list(principal, page, limit);
    return ApiResponse.ok(result.sessions(), result.meta());
  }

  @DeleteMapping("/sessions/{sessionId}")
  public ApiResponse<RevokeSessionResponse> revokeSession(
      @PathVariable UUID sessionId, @AuthenticationPrincipal MedmatePrincipal principal) {
    requireAuth(principal);
    UUID revoked = logoutService.revokeSession(principal, sessionId);
    return ApiResponse.ok(RevokeSessionResponse.of(revoked));
  }

  private static void requireAuth(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
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

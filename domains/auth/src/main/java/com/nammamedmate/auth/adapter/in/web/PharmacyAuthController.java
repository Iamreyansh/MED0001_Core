package com.nammamedmate.auth.adapter.in.web;

import com.nammamedmate.auth.adapter.in.web.dto.CompletePharmacyInviteRequest;
import com.nammamedmate.auth.adapter.in.web.dto.CompletePharmacyResetRequest;
import com.nammamedmate.auth.adapter.in.web.dto.ForgotPharmacyPasswordRequest;
import com.nammamedmate.auth.adapter.in.web.dto.PharmacyLoginRequest;
import com.nammamedmate.auth.adapter.in.web.dto.PharmacyLoginResponse;
import com.nammamedmate.auth.adapter.in.web.dto.PosPinLoginRequest;
import com.nammamedmate.auth.adapter.in.web.dto.PosPinLoginResponse;
import com.nammamedmate.auth.adapter.in.web.dto.SwitchPharmacyRequest;
import com.nammamedmate.auth.adapter.in.web.dto.SwitchPharmacyResponse;
import com.nammamedmate.auth.application.PharmacyLoginService;
import com.nammamedmate.auth.application.PharmacyStaffService;
import com.nammamedmate.auth.application.PosPinLoginService;
import com.nammamedmate.auth.application.SwitchPharmacyService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/pharmacy")
public class PharmacyAuthController {

  private final PharmacyLoginService loginService;
  private final SwitchPharmacyService switchService;
  private final PosPinLoginService posPinService;
  private final PharmacyStaffService staffService;

  public PharmacyAuthController(
      PharmacyLoginService loginService,
      SwitchPharmacyService switchService,
      PosPinLoginService posPinService,
      PharmacyStaffService staffService) {
    this.loginService = loginService;
    this.switchService = switchService;
    this.posPinService = posPinService;
    this.staffService = staffService;
  }

  @PostMapping("/login")
  public ApiResponse<PharmacyLoginResponse> login(
      @Valid @RequestBody PharmacyLoginRequest request, HttpServletRequest http) {
    var result =
        loginService.login(
            request.identifier(),
            request.password(),
            request.pharmacyId(),
            clientIp(http),
            http.getHeader("User-Agent"));
    return ApiResponse.ok(PharmacyLoginResponse.from(result));
  }

  @PostMapping("/switch-pharmacy")
  public ApiResponse<SwitchPharmacyResponse> switchPharmacy(
      @Valid @RequestBody SwitchPharmacyRequest request,
      @AuthenticationPrincipal MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.tokenScope() == TokenScope.POS) {
      throw new AppException(
          "POS_TOKEN_RESTRICTED", "POS token cannot be used on this endpoint", 403);
    }
    var result = switchService.switchPharmacy(principal.subject(), request.pharmacyId());
    return ApiResponse.ok(SwitchPharmacyResponse.from(result));
  }

  @PostMapping("/complete-invite")
  public ApiResponse<Map<String, Object>> completeInvite(
      @RequestBody(required = false) CompletePharmacyInviteRequest request) {
    CompletePharmacyInviteRequest body =
        request == null ? new CompletePharmacyInviteRequest(null, null) : request;
    return ApiResponse.ok(staffService.completeInvite(body.token(), body.password()));
  }

  @PostMapping("/forgot-password")
  public ApiResponse<Map<String, Object>> forgotPassword(
      @RequestBody(required = false) ForgotPharmacyPasswordRequest request) {
    ForgotPharmacyPasswordRequest body =
        request == null ? new ForgotPharmacyPasswordRequest(null) : request;
    return ApiResponse.ok(staffService.requestPasswordReset(body.identifier()));
  }

  @PostMapping("/complete-reset")
  public ApiResponse<Map<String, Object>> completeReset(
      @RequestBody(required = false) CompletePharmacyResetRequest request) {
    CompletePharmacyResetRequest body =
        request == null ? new CompletePharmacyResetRequest(null, null) : request;
    return ApiResponse.ok(staffService.completePasswordReset(body.token(), body.password()));
  }

  @PostMapping("/pos-pin")
  public ApiResponse<PosPinLoginResponse> posPinLogin(
      @Valid @RequestBody PosPinLoginRequest request, HttpServletRequest http) {
    var result =
        posPinService.login(
            request.pharmacyId(),
            request.staffId(),
            request.pin(),
            clientIp(http),
            http.getHeader("User-Agent"));
    return ApiResponse.ok(PosPinLoginResponse.from(result));
  }

  /** Uses remote address only — X-Forwarded-For is client-spoofable without a trusted proxy. */
  private static String clientIp(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    return remote == null || remote.isBlank() ? "0.0.0.0" : remote;
  }
}

package com.nammamedmate.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.auth.adapter.in.web.dto.AdminLoginRequest;
import com.nammamedmate.auth.adapter.in.web.dto.AdminLoginResponse;
import com.nammamedmate.auth.adapter.in.web.dto.AdminSetupMfaResponse;
import com.nammamedmate.auth.adapter.in.web.dto.AdminVerifyMfaRequest;
import com.nammamedmate.auth.adapter.in.web.dto.AdminVerifyMfaResponse;
import com.nammamedmate.auth.application.AdminInviteCompleteService;
import com.nammamedmate.auth.application.AdminLoginResult;
import com.nammamedmate.auth.application.AdminLoginService;
import com.nammamedmate.auth.application.AdminMfaVerifyResult;
import com.nammamedmate.auth.application.AdminSetupMfaResult;
import com.nammamedmate.auth.application.AdminSetupMfaService;
import com.nammamedmate.auth.application.AdminVerifyMfaService;
import com.nammamedmate.auth.application.port.out.AdminStaffRecord;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;

class AdminAuthControllerTest {

  private final AdminLoginService loginService = mock(AdminLoginService.class);
  private final AdminVerifyMfaService verifyMfaService = mock(AdminVerifyMfaService.class);
  private final AdminSetupMfaService setupMfaService = mock(AdminSetupMfaService.class);
  private final AdminInviteCompleteService inviteCompleteService =
      mock(AdminInviteCompleteService.class);
  private final AdminAuthController controller =
      new AdminAuthController(
          loginService, verifyMfaService, setupMfaService, inviteCompleteService);

  @Test
  void loginMapsRequestAndUsesForwardedFor() {
    UUID adminId = Ids.newId();
    when(loginService.login(eq("ops@test.in"), eq("Passw0rd!"), eq("10.0.0.1"), eq("ua")))
        .thenReturn(AdminLoginResult.challenge("challenge", 300, adminId));

    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1, 10.0.0.2");
    when(http.getHeader("User-Agent")).thenReturn("ua");

    ApiResponse<AdminLoginResponse> response =
        controller.login(new AdminLoginRequest("ops@test.in", "Passw0rd!"), http);

    assertThat(response.success()).isTrue();
    assertThat(response.data().mfaRequired()).isTrue();
    assertThat(response.data().mfaChallengeToken()).isEqualTo("challenge");
    verify(loginService).login("ops@test.in", "Passw0rd!", "10.0.0.1", "ua");
  }

  @Test
  void loginFallsBackToRemoteAddrWhenNoForwardedFor() {
    when(loginService.login(any(), any(), eq("127.0.0.1"), any()))
        .thenReturn(AdminLoginResult.tokens("a", "r", 900, 28_800, adminRecord()));

    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getHeader("X-Forwarded-For")).thenReturn(null);
    when(http.getRemoteAddr()).thenReturn("127.0.0.1");

    controller.login(new AdminLoginRequest("ops@test.in", "Passw0rd!"), http);

    verify(loginService).login("ops@test.in", "Passw0rd!", "127.0.0.1", null);
  }

  @Test
  void loginDefaultsIpWhenRemoteNull() {
    when(loginService.login(any(), any(), eq("0.0.0.0"), any()))
        .thenReturn(AdminLoginResult.tokens("a", "r", 900, 28_800, adminRecord()));

    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getHeader("X-Forwarded-For")).thenReturn("   ");
    when(http.getRemoteAddr()).thenReturn(null);

    controller.login(new AdminLoginRequest("ops@test.in", "Passw0rd!"), http);

    verify(loginService).login("ops@test.in", "Passw0rd!", "0.0.0.0", null);
  }

  @Test
  void loginDefaultsIpWhenRemoteBlank() {
    when(loginService.login(any(), any(), eq("0.0.0.0"), any()))
        .thenReturn(AdminLoginResult.tokens("a", "r", 900, 28_800, adminRecord()));

    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getRemoteAddr()).thenReturn("  ");

    controller.login(new AdminLoginRequest("ops@test.in", "Passw0rd!"), http);

    verify(loginService).login("ops@test.in", "Passw0rd!", "0.0.0.0", null);
  }

  @Test
  void verifyMfaRequiresChallengePrincipalAndMatchingBearer() {
    AdminStaffRecord admin = adminRecord();
    when(verifyMfaService.verify(eq("challenge"), eq("123456"), eq("1.2.3.4"), eq("ua")))
        .thenReturn(new AdminMfaVerifyResult("access", "refresh", 900, 28_800, false, admin, 8));

    MedmatePrincipal principal =
        new MedmatePrincipal(
            admin.id(), AuthRole.ADMIN_SUPER, null, TokenScope.MFA_CHALLENGE, "jti");
    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer challenge");
    when(http.getRemoteAddr()).thenReturn("1.2.3.4");
    when(http.getHeader("User-Agent")).thenReturn("ua");

    ApiResponse<AdminVerifyMfaResponse> response =
        controller.verifyMfa(new AdminVerifyMfaRequest("challenge", "123456"), principal, http);

    assertThat(response.data().accessToken()).isEqualTo("access");
    assertThat(response.data().admin().mfaEnabled()).isTrue();
  }

  @Test
  void verifyMfaRejectsMissingPrincipal() {
    HttpServletRequest http = mock(HttpServletRequest.class);
    assertThatThrownBy(
            () ->
                controller.verifyMfa(new AdminVerifyMfaRequest("challenge", "123456"), null, http))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CHALLENGE_TOKEN_INVALID");
  }

  @Test
  void verifyMfaRejectsNonChallengeScope() {
    MedmatePrincipal principal =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "jti");
    HttpServletRequest http = mock(HttpServletRequest.class);
    assertThatThrownBy(
            () ->
                controller.verifyMfa(
                    new AdminVerifyMfaRequest("challenge", "123456"), principal, http))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CHALLENGE_TOKEN_INVALID");
  }

  @Test
  void verifyMfaRejectsBearerBodyMismatch() {
    MedmatePrincipal principal =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.MFA_CHALLENGE, "jti");
    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer other-token");

    assertThatThrownBy(
            () ->
                controller.verifyMfa(
                    new AdminVerifyMfaRequest("challenge", "123456"), principal, http))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CHALLENGE_TOKEN_INVALID");
  }

  @Test
  void verifyMfaRejectsMissingBearer() {
    MedmatePrincipal principal =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.MFA_CHALLENGE, "jti");
    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn(null);

    assertThatThrownBy(
            () ->
                controller.verifyMfa(
                    new AdminVerifyMfaRequest("challenge", "123456"), principal, http))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CHALLENGE_TOKEN_INVALID");
  }

  @Test
  void verifyMfaRejectsBlankBearer() {
    MedmatePrincipal principal =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.MFA_CHALLENGE, "jti");
    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer   ");

    assertThatThrownBy(
            () ->
                controller.verifyMfa(
                    new AdminVerifyMfaRequest("challenge", "123456"), principal, http))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CHALLENGE_TOKEN_INVALID");
  }

  @Test
  void verifyMfaRejectsNonBearerAuthorization() {
    MedmatePrincipal principal =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.MFA_CHALLENGE, "jti");
    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Basic abc");

    assertThatThrownBy(
            () ->
                controller.verifyMfa(
                    new AdminVerifyMfaRequest("challenge", "123456"), principal, http))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CHALLENGE_TOKEN_INVALID");
  }

  @Test
  void setupMfaRequiresPrincipal() {
    assertThatThrownBy(() -> controller.setupMfa(Map.of(), null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void setupMfaRejectsChallengeToken() {
    MedmatePrincipal principal =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.MFA_CHALLENGE, "jti");

    assertThatThrownBy(() -> controller.setupMfa(Map.of(), principal))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void setupMfaRejectsPosToken() {
    MedmatePrincipal principal =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.POS, "jti");

    assertThatThrownBy(() -> controller.setupMfa(Map.of(), principal))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void setupMfaHappyPath() {
    UUID adminId = Ids.newId();
    MedmatePrincipal principal =
        new MedmatePrincipal(adminId, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "jti");
    when(setupMfaService.setup(adminId))
        .thenReturn(new AdminSetupMfaResult("otpauth://totp/x", "SECRET", List.of("ABCD-1234")));

    ApiResponse<AdminSetupMfaResponse> response = controller.setupMfa(null, principal);

    assertThat(response.data().totpSecret()).isEqualTo("SECRET");
    assertThat(response.data().backupCodes()).containsExactly("ABCD-1234");
    ArgumentCaptor<UUID> captor = ArgumentCaptor.forClass(UUID.class);
    verify(setupMfaService).setup(captor.capture());
    assertThat(captor.getValue()).isEqualTo(adminId);
  }

  private static AdminStaffRecord adminRecord() {
    Instant now = Instant.now();
    return new AdminStaffRecord(
        Ids.newId(),
        "Ops",
        "ops@test.in",
        "hash",
        "admin_operations",
        "ACTIVE",
        true,
        "enc",
        List.of(),
        0,
        null,
        null,
        now,
        now,
        null,
        now,
        now);
  }

  @Test
  void completeInviteDelegates() {
    when(inviteCompleteService.complete(eq("tok"), eq("Passw0rd!")))
        .thenReturn(Map.of("status", "ACTIVE"));
    when(inviteCompleteService.complete(null, null)).thenReturn(Map.of("status", "ACTIVE"));
    assertThat(controller.completeInvite(Map.of("invite_token", "tok", "password", "Passw0rd!")))
        .extracting(ApiResponse::success)
        .isEqualTo(true);
    assertThat(controller.completeInvite(null).success()).isTrue();
  }
}

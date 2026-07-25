package com.nammamedmate.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.nammamedmate.auth.application.TokenPairResult;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenAuthControllerTest {

  @Mock RefreshTokenService refreshTokenService;
  @Mock SessionLogoutService logoutService;
  @Mock CurrentUserService currentUserService;
  @Mock SessionListService sessionListService;
  @Mock HttpServletRequest http;
  @InjectMocks TokenAuthController controller;

  @Test
  void refreshDelegates() {
    when(http.getRemoteAddr()).thenReturn("127.0.0.1");
    when(refreshTokenService.refresh(eq("rt"), eq("127.0.0.1")))
        .thenReturn(new TokenPairResult("a", "r", "Bearer", 900, 1000, Ids.newId()));
    ApiResponse<TokenPairResponse> response =
        controller.refresh(new RefreshTokenRequest("rt"), http);
    assertThat(response.success()).isTrue();
    assertThat(response.data().accessToken()).isEqualTo("a");
  }

  @Test
  void logoutAndLogoutAllAndMeAndSessionsAndRevoke() {
    MedmatePrincipal principal =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "jti");
    ApiResponse<LogoutResponse> logout = controller.logout(new LogoutRequest("rt"), principal);
    verify(logoutService).logout(principal, "rt");
    assertThat(logout.data().message()).contains("terminated");

    when(logoutService.logoutAll(principal)).thenReturn(2);
    ApiResponse<LogoutAllResponse> all = controller.logoutAll(principal);
    assertThat(all.data().sessionsRevoked()).isEqualTo(2);

    when(currentUserService.me(principal)).thenReturn(Map.of("role", "customer"));
    assertThat(controller.me(principal).data().get("role")).isEqualTo("customer");

    when(sessionListService.list(eq(principal), isNull(), isNull()))
        .thenReturn(
            new SessionListService.SessionListResult(
                List.of(Map.of("session_id", Ids.newId())), PaginationMeta.of(1, 20, 1)));
    assertThat(controller.sessions(principal, null, null).data()).hasSize(1);

    UUID sessionId = Ids.newId();
    when(logoutService.revokeSession(principal, sessionId)).thenReturn(sessionId);
    ApiResponse<RevokeSessionResponse> revoked = controller.revokeSession(sessionId, principal);
    assertThat(revoked.data().sessionId()).isEqualTo(sessionId);
  }

  @Test
  void requireAuthAndClientIpBranches() {
    assertThatThrownBy(() -> controller.me(null))
        .isInstanceOf(com.nammamedmate.kernel.error.AppException.class);

    when(http.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1, 10.0.0.2");
    when(refreshTokenService.refresh(eq("rt"), eq("10.0.0.1")))
        .thenReturn(new TokenPairResult("a", "r", "Bearer", 900, 1000, Ids.newId()));
    assertThat(controller.refresh(new RefreshTokenRequest("rt"), http).data().accessToken())
        .isEqualTo("a");

    when(http.getHeader("X-Forwarded-For")).thenReturn(" ");
    when(http.getRemoteAddr()).thenReturn(null);
    when(refreshTokenService.refresh(eq("rt2"), eq("0.0.0.0")))
        .thenReturn(new TokenPairResult("a2", "r2", "Bearer", 900, 1000, Ids.newId()));
    assertThat(controller.refresh(new RefreshTokenRequest("rt2"), http).data().accessToken())
        .isEqualTo("a2");

    when(http.getHeader("X-Forwarded-For")).thenReturn(null);
    when(http.getRemoteAddr()).thenReturn(" ");
    when(refreshTokenService.refresh(eq("rt3"), eq("0.0.0.0")))
        .thenReturn(new TokenPairResult("a3", "r3", "Bearer", 900, 1000, Ids.newId()));
    assertThat(controller.refresh(new RefreshTokenRequest("rt3"), http).data().accessToken())
        .isEqualTo("a3");
  }
}

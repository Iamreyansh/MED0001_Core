package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.AuthSessionRecord;
import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import com.nammamedmate.auth.domain.RefreshTokens;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenRevocationStore;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionLogoutService {

  static final int LOGOUT_LIMIT = 30;
  static final int LOGOUT_WINDOW_SECONDS = 60;
  static final int LOGOUT_ALL_LIMIT = 5;
  static final int LOGOUT_ALL_WINDOW_SECONDS = 3600;
  static final long ACCESS_REVOKE_TTL_SECONDS = 900L;

  private final AuthSessionStore sessionStore;
  private final TokenRevocationStore revocationStore;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public SessionLogoutService(
      AuthSessionStore sessionStore,
      TokenRevocationStore revocationStore,
      RateLimiter rateLimiter,
      Clock clock) {
    this.sessionStore = sessionStore;
    this.revocationStore = revocationStore;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional
  public void logout(MedmatePrincipal principal, String refreshToken) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "refresh_token is required", 400);
    }
    rateLimitUser(
        "auth:user:logout:" + principal.subject() + ":count", LOGOUT_LIMIT, LOGOUT_WINDOW_SECONDS);

    String hash = RefreshTokens.sha256Hex(refreshToken);
    AuthSessionRecord session =
        sessionStore
            .findByRefreshTokenHash(hash)
            .orElseThrow(() -> new AppException("SESSION_NOT_FOUND", "Session not found", 404));

    if (!session.userId().equals(principal.subject())
        || session.revokedAt() != null
        || session.rotatedAt() != null) {
      throw new AppException("SESSION_NOT_FOUND", "Session not found", 404);
    }

    Instant now = clock.instant();
    if (sessionStore.revokeIfActive(session.id(), now) == 0) {
      throw new AppException("SESSION_NOT_FOUND", "Session not found", 404);
    }
    revocationStore.revoke(principal.jti(), ACCESS_REVOKE_TTL_SECONDS);
  }

  @Transactional
  public int logoutAll(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    rateLimitUser(
        "auth:user:logout-all:" + principal.subject() + ":count",
        LOGOUT_ALL_LIMIT,
        LOGOUT_ALL_WINDOW_SECONDS);

    Instant now = clock.instant();
    int revoked = sessionStore.revokeAllForUser(principal.subject(), now);
    revocationStore.revoke(principal.jti(), ACCESS_REVOKE_TTL_SECONDS);
    return revoked;
  }

  @Transactional
  public UUID revokeSession(MedmatePrincipal principal, UUID sessionId) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (sessionId == null) {
      throw new AppException("VALIDATION_ERROR", "session_id is required", 400);
    }
    rateLimitUser("auth:user:revoke-session:" + principal.subject() + ":count", 10, 60);

    AuthSessionRecord session =
        sessionStore
            .findById(sessionId)
            .orElseThrow(() -> new AppException("SESSION_NOT_FOUND", "Session not found", 404));

    if (!session.userId().equals(principal.subject())) {
      // Non-admin ownership: this endpoint never allows cross-user revoke (admin API is separate).
      throw new AppException("FORBIDDEN", "Cannot revoke another user's session", 403);
    }
    if (session.revokedAt() != null || session.rotatedAt() != null) {
      throw new AppException("SESSION_NOT_FOUND", "Session not found", 404);
    }

    Instant now = clock.instant();
    if (sessionStore.revokeIfActive(session.id(), now) == 0) {
      throw new AppException("SESSION_NOT_FOUND", "Session not found", 404);
    }
    return session.id();
  }

  private void rateLimitUser(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      throw new AppException(
          "RATE_LIMITED",
          "Rate limit exceeded",
          429,
          rateLimiter.secondsUntilAvailable(key, limit, windowSeconds));
    }
  }
}

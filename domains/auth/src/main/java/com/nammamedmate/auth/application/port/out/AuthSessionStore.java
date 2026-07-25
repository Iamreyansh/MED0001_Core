package com.nammamedmate.auth.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionStore {

  AuthSessionRecord save(AuthSessionRecord session);

  Optional<AuthSessionRecord> findByRefreshTokenHash(String refreshTokenHash);

  Optional<AuthSessionRecord> findById(UUID id);

  /** Atomically set rotated_at when still active; returns rows updated (0 or 1). */
  int markRotatedIfActive(UUID id, Instant rotatedAt);

  /** Soft-revoke one session if not already revoked; returns rows updated. */
  int revokeIfActive(UUID id, Instant revokedAt);

  /** Soft-revoke all non-revoked sessions for user; returns count revoked. */
  int revokeAllForUser(UUID userId, Instant revokedAt);

  /** page is 1-based. */
  List<AuthSessionRecord> listActiveByUserId(UUID userId, Instant now, int page, int limit);

  long countActiveByUserId(UUID userId, Instant now);
}

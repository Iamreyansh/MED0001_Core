package com.nammamedmate.rider.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** Revokes auth sessions for a rider without a domain→auth compile dependency. */
public interface RiderSessionRevokePort {

  int revokeAllForUser(UUID userId, Instant revokedAt);
}

package com.nammamedmate.auth.application.port.out;

import java.time.Instant;
import java.util.UUID;

public record AuthSessionRecord(
    UUID id,
    UUID userId,
    String userType,
    String refreshTokenHash,
    String tokenScope,
    String deviceInfoJson,
    String ipAddress,
    String userAgent,
    Instant createdAt,
    Instant lastActiveAt,
    Instant expiresAt,
    UUID pharmacyId,
    String country,
    String city,
    Instant rotatedAt,
    Instant revokedAt) {

  /** Convenience for login issuers that leave geo/rotation unset. */
  public static AuthSessionRecord active(
      UUID id,
      UUID userId,
      String userType,
      String refreshTokenHash,
      String tokenScope,
      String deviceInfoJson,
      String ipAddress,
      String userAgent,
      Instant createdAt,
      Instant lastActiveAt,
      Instant expiresAt,
      UUID pharmacyId) {
    return new AuthSessionRecord(
        id,
        userId,
        userType,
        refreshTokenHash,
        tokenScope,
        deviceInfoJson,
        ipAddress,
        userAgent,
        createdAt,
        lastActiveAt,
        expiresAt,
        pharmacyId,
        null,
        null,
        null,
        null);
  }
}

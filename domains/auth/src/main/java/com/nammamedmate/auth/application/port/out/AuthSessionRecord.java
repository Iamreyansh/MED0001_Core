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
    Instant expiresAt) {}

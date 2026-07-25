package com.nammamedmate.auth.application.port.out;

import java.time.Instant;
import java.util.UUID;

public record OtpSessionRecord(
    UUID id,
    String phone,
    String otpHash,
    int attempts,
    String deviceInfoJson,
    Instant expiresAt,
    Instant verifiedAt,
    Instant lockedAt,
    Instant createdAt) {}

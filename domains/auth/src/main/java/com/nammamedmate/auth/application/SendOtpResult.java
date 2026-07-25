package com.nammamedmate.auth.application;

import java.time.Instant;
import java.util.UUID;

public record SendOtpResult(
    UUID sessionId,
    String phone,
    Instant expiresAt,
    Instant resendAllowedAt,
    int attemptsRemaining) {}

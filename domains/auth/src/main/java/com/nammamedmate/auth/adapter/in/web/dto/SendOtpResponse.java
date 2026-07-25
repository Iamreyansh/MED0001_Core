package com.nammamedmate.auth.adapter.in.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SendOtpResponse(
    UUID sessionId,
    String phone,
    Instant expiresAt,
    Instant resendAllowedAt,
    int attemptsRemaining) {}

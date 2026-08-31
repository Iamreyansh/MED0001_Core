package com.nammamedmate.auth.application.port.out;

import java.time.Instant;
import java.util.UUID;

public record PharmacyStaffPasswordResetRecord(
    UUID id,
    UUID staffId,
    String tokenHash,
    Instant expiresAt,
    Instant usedAt,
    Instant createdAt) {}

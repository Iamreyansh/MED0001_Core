package com.nammamedmate.auth.application.port.out;

import java.time.Instant;
import java.util.UUID;

public record PharmacyStaffInviteRecord(
    UUID id,
    UUID staffId,
    UUID pharmacyId,
    String tokenHash,
    Instant expiresAt,
    Instant usedAt,
    Instant createdAt) {}

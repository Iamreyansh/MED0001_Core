package com.nammamedmate.auth.application.port.out;

import java.time.Instant;
import java.util.UUID;

public record PharmacyStaffRecord(
    UUID id,
    String name,
    String email,
    String phone,
    String passwordHash,
    String posPinHash,
    String status,
    int failedLoginAttempts,
    Instant lockedUntil,
    Instant lastFailedAt,
    Instant lastLoginAt,
    UUID invitedBy,
    Instant createdAt,
    Instant updatedAt) {}

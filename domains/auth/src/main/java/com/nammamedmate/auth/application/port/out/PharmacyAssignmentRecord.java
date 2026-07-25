package com.nammamedmate.auth.application.port.out;

import java.time.Instant;
import java.util.UUID;

public record PharmacyAssignmentRecord(
    UUID id,
    UUID staffId,
    UUID pharmacyId,
    String roleCode,
    boolean isActive,
    Instant joinedAt,
    Instant removedAt,
    String pharmacyName) {}

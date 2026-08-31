package com.nammamedmate.auth.application.port.out;

import java.time.Instant;
import java.util.UUID;

public record PharmacyStaffDirectoryRow(
    UUID staffId,
    String name,
    String email,
    String phone,
    String status,
    String roleCode,
    boolean active,
    Instant joinedAt,
    boolean posPinSet) {}

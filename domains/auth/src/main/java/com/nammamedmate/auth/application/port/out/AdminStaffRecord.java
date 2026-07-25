package com.nammamedmate.auth.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AdminStaffRecord(
    UUID id,
    String name,
    String email,
    String passwordHash,
    String role,
    String status,
    boolean mfaEnabled,
    String encryptedTotpSecret,
    List<Map<String, Object>> backupCodes,
    int failedLoginAttempts,
    Instant lockedUntil,
    Instant lastFailedAt,
    Instant lastLoginAt,
    Instant lastActiveAt,
    UUID invitedBy,
    Instant createdAt,
    Instant updatedAt) {

  public AdminStaffRecord {
    backupCodes = backupCodes == null ? List.of() : List.copyOf(backupCodes);
  }
}

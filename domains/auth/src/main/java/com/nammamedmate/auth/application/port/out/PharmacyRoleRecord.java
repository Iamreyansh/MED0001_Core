package com.nammamedmate.auth.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PharmacyRoleRecord(
    UUID id,
    UUID pharmacyId,
    String code,
    String displayName,
    boolean system,
    List<String> permissions,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public PharmacyRoleRecord {
    permissions = permissions == null ? List.of() : List.copyOf(permissions);
  }
}

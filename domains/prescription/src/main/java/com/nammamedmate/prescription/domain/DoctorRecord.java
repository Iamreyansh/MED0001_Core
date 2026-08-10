package com.nammamedmate.prescription.domain;

import java.time.Instant;
import java.util.UUID;

public record DoctorRecord(
    UUID id,
    String registrationNo,
    String name,
    String qualification,
    String specialty,
    String status,
    String source,
    int prescriptionCount,
    int scheduledDrugCount,
    String verificationMethod,
    UUID verifiedBy,
    Instant verifiedAt,
    String verificationNotes,
    String blacklistReason,
    UUID blacklistedBy,
    Instant blacklistedAt,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public boolean blacklisted() {
    return "BLACKLISTED".equals(status);
  }

  public boolean verified() {
    return "VERIFIED".equals(status);
  }
}

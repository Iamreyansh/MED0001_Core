package com.nammamedmate.pharmacy.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyEmailOtpStore {

  record OtpRecord(
      UUID id,
      UUID pharmacyId,
      String email,
      String otpHash,
      int attempts,
      int resendCount,
      Instant expiresAt,
      Instant verifiedAt,
      Instant lockedAt,
      Instant lastSentAt,
      Instant createdAt) {}

  void insert(OtpRecord record);

  void update(OtpRecord record);

  Optional<OtpRecord> findLatestByEmail(String email);
}

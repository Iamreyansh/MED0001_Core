package com.nammamedmate.pharmacy.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyProfileOtpStore {

  record OtpRecord(
      UUID id,
      UUID pharmacyId,
      String channel,
      String targetValue,
      String otpHash,
      Instant expiresAt,
      int attempts,
      Instant createdAt) {}

  void insert(OtpRecord record);

  void update(OtpRecord record);

  void deleteByPharmacyAndChannel(UUID pharmacyId, String channel);

  Optional<OtpRecord> findLatest(UUID pharmacyId, String channel);
}

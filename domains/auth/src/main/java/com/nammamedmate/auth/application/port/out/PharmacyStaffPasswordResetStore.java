package com.nammamedmate.auth.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyStaffPasswordResetStore {

  void insert(PharmacyStaffPasswordResetRecord reset);

  Optional<PharmacyStaffPasswordResetRecord> findActiveByTokenHash(String tokenHash);

  void markUsed(UUID id, Instant usedAt);
}

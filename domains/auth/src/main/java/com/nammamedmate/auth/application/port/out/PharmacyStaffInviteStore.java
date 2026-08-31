package com.nammamedmate.auth.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyStaffInviteStore {

  void insert(PharmacyStaffInviteRecord invite);

  Optional<PharmacyStaffInviteRecord> findActiveByTokenHash(String tokenHash);

  void markUsed(UUID id, Instant usedAt);
}

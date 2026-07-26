package com.nammamedmate.pharmacy.application.port.out;

import java.time.Instant;
import java.util.UUID;

public interface PharmacyOwnerAccountStore {

  UUID OWNER_ROLE_ID = UUID.fromString("00000000-0000-0000-0001-000000000001");

  record OwnerCreate(
      UUID staffId,
      String name,
      String email,
      String phone,
      String passwordHash,
      UUID pharmacyId,
      UUID roleId,
      Instant now) {}

  void createOwner(OwnerCreate cmd);

  /** Promote INVITED owner to ACTIVE after email OTP verification. */
  void activateOwner(UUID staffId, Instant now);

  java.util.Optional<UUID> findStaffIdByEmail(String email);

  boolean emailTakenPlatformWide(String email);

  boolean phoneTakenPlatformWide(String phone);
}

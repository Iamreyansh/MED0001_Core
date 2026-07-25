package com.nammamedmate.auth.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface PharmacyStaffStore {

  Optional<PharmacyStaffRecord> findByEmail(String email);

  Optional<PharmacyStaffRecord> findByPhone(String phone);

  Optional<PharmacyStaffRecord> findById(UUID id);

  PharmacyStaffRecord save(PharmacyStaffRecord staff);
}

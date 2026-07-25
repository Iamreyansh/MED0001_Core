package com.nammamedmate.auth.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface AdminStaffStore {
  Optional<AdminStaffRecord> findByEmail(String email);

  Optional<AdminStaffRecord> findById(UUID id);

  AdminStaffRecord save(AdminStaffRecord staff);
}

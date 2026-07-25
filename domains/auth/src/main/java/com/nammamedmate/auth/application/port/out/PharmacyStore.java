package com.nammamedmate.auth.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface PharmacyStore {

  Optional<PharmacyRecord> findById(UUID id);
}

package com.nammamedmate.pharmacy.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface ZoneStore {

  record ZoneRecord(UUID id, String name, boolean active) {}

  Optional<ZoneRecord> findById(UUID id);
}

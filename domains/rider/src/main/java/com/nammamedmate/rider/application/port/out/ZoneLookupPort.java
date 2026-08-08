package com.nammamedmate.rider.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface ZoneLookupPort {

  record ZoneInfo(UUID id, String name, boolean active) {}

  Optional<ZoneInfo> findById(UUID zoneId);
}

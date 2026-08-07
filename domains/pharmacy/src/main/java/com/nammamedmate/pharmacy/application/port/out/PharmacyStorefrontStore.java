package com.nammamedmate.pharmacy.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyStorefrontStore {

  record StorefrontRow(
      UUID pharmacyId,
      String status,
      boolean online,
      boolean adminForcedOffline,
      UUID zoneId,
      String zoneName) {}

  Optional<StorefrontRow> findStorefront(UUID pharmacyId);

  void updateOnlineStatus(
      UUID pharmacyId, boolean isOnline, boolean adminForcedOffline, Instant updatedAt);

  void updateZone(UUID pharmacyId, UUID zoneId, Instant updatedAt);
}

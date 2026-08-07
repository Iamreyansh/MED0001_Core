package com.nammamedmate.catalogue.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Catalogue-owned pharmacy/zone read port (JDBC against pharmacies + pincode_zone_mapping). No
 * domain→pharmacy compile dependency.
 */
public interface ZonePharmacyLookupPort {

  record PharmacyRef(
      UUID id,
      String name,
      UUID zoneId,
      boolean online,
      boolean adminForcedOffline,
      String status) {}

  Optional<PharmacyRef> findById(UUID pharmacyId);

  Optional<UUID> zoneIdForPincode(String pincode);
}

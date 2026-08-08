package com.nammamedmate.rider.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Pharmacy + address reads for public fee estimate (no domain→domain deps). */
public interface DeliveryPricingLookupPort {

  record PharmacyGeo(UUID id, String name, double lat, double lng) {}

  record AddressGeo(UUID id, double lat, double lng) {}

  Optional<PharmacyGeo> findPharmacy(UUID pharmacyId);

  Optional<AddressGeo> findAddress(UUID addressId);
}

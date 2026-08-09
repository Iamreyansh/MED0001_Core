package com.nammamedmate.payment.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Pharmacy tax identity for TCS register (bridged in apps/api). */
public interface TaxPharmacyProfilePort {

  record PharmacyTaxProfile(UUID pharmacyId, String businessName, String gstin, String pan) {}

  Optional<PharmacyTaxProfile> find(UUID pharmacyId);
}

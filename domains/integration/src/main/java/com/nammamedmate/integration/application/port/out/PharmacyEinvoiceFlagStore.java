package com.nammamedmate.integration.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Reads {@code pharmacies.e_invoicing_enabled} without a domain→domain dependency. */
public interface PharmacyEinvoiceFlagStore {

  Optional<Boolean> findEInvoicingEnabled(UUID pharmacyId);
}

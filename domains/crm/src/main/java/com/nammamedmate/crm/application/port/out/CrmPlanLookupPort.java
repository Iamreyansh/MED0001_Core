package com.nammamedmate.crm.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** Lookup current CRM plan for a pharmacy (composition-root bridges Pos/Inventory gates). */
public interface CrmPlanLookupPort {

  Optional<String> planNameForPharmacy(UUID pharmacyId);

  boolean planIncludesModule(String planName, String moduleId);

  /**
   * Effective module access: account override beats plan matrix when present. Missing account falls
   * back to FREE plan matrix.
   */
  boolean moduleAccessibleForPharmacy(UUID pharmacyId, String moduleId);
}

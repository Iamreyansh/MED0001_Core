package com.nammamedmate.pharmacy.application.port.out;

import java.util.UUID;

/** Optional CRM bootstrap after pharmacy registration (wired in apps/api). */
@FunctionalInterface
public interface CrmAccountBootstrapPort {

  void ensureFreeSubscription(UUID pharmacyId);
}

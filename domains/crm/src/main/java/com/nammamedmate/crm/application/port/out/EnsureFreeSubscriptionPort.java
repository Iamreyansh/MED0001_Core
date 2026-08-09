package com.nammamedmate.crm.application.port.out;

import java.util.UUID;

/** Bootstrap crm_account + FREE ACTIVE subscription for a newly registered pharmacy. */
@FunctionalInterface
public interface EnsureFreeSubscriptionPort {

  void ensureFreeSubscription(UUID pharmacyId);
}

package com.nammamedmate.crm.application.port.out;

import java.util.UUID;

/** Marketplace registration → CONTACTED lead (wired from apps/api bridge). */
@FunctionalInterface
public interface EnsureMarketplaceLeadPort {

  void ensureMarketplaceLead(
      UUID pharmacyId, String pharmacyName, String contactName, String phone, String email);
}

package com.nammamedmate.crm.application.port.out;

import java.util.UUID;

/** Sync legacy pharmacies.plan / subscription_plan from CRM plan names. */
public interface PharmacyPlanSyncPort {

  void syncPlan(UUID pharmacyId, String crmPlanName);
}

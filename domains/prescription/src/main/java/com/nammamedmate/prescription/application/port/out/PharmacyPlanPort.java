package com.nammamedmate.prescription.application.port.out;

import java.util.UUID;

/** Starter+ unlocks pharmacy Rx queue (FREE fails with PLAN_UPGRADE_REQUIRED). */
public interface PharmacyPlanPort {

  boolean rxQueueEnabled(UUID pharmacyId);
}

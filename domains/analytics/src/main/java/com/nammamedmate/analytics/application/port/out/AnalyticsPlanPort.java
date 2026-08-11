package com.nammamedmate.analytics.application.port.out;

import java.util.UUID;

/** Growth+ plan gate: GROWTH / RETAIL_PRO / ENTERPRISE (Free/Starter denied). */
@FunctionalInterface
public interface AnalyticsPlanPort {

  boolean allowsPharmacyAnalytics(UUID pharmacyId);
}

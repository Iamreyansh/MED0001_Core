package com.nammamedmate.integration.application.port.out;

import java.util.UUID;

/** Growth+ plan gate: GROWTH / RETAIL_PRO / ENTERPRISE (and legacy PRO). */
@FunctionalInterface
public interface AccountingPlanPort {

  boolean allowsAccounting(UUID pharmacyId);
}

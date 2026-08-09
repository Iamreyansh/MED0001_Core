package com.nammamedmate.crm.application.port.out;

import java.util.UUID;

/** Best-effort ERP usage metering for pharmacy dashboard calls. */
public interface ModuleUsageMeterPort {

  void recordUsage(UUID pharmacyId, String moduleId);
}

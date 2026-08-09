package com.nammamedmate.crm.application.port.out;

import java.util.UUID;

/** GMV / ERP volume growth until order module / marketplace wiring. */
public interface BusinessPerformancePort {

  double scoreForAccount(UUID accountId, UUID pharmacyId);
}

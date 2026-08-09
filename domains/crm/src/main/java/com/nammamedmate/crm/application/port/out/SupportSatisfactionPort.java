package com.nammamedmate.crm.application.port.out;

import java.util.UUID;

/** Support NPS / ticket health until EPIC-015. */
public interface SupportSatisfactionPort {

  double scoreForAccount(UUID accountId);
}

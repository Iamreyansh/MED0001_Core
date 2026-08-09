package com.nammamedmate.crm.application.port.out;

import java.util.Map;
import java.util.UUID;

/** Ids-only outbox for lead assignment notifications. */
@FunctionalInterface
public interface CrmLeadOutboxPort {

  void publish(String type, UUID aggregateId, Map<String, Object> payload);
}

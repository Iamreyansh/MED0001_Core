package com.nammamedmate.crm.application.port.out;

import java.util.Map;
import java.util.UUID;

/** Ids-only outbox stubs for churn survey / dunning (STORY-003 owns real delivery). */
public interface CrmSubscriptionOutboxPort {

  void publish(String type, UUID aggregateId, Map<String, Object> payload);
}

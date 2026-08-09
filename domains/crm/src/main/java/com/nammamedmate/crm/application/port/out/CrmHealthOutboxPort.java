package com.nammamedmate.crm.application.port.out;

import java.util.Map;
import java.util.UUID;

/** Outbox publisher for account health save-play alerts (ids-only payloads). */
public interface CrmHealthOutboxPort {

  void publish(String type, UUID aggregateId, Map<String, Object> payload);
}

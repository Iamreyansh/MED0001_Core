package com.nammamedmate.integration.application.port.out;

import java.util.Map;
import java.util.UUID;

/** Outbox / bridge side-effects (ids-only payloads). */
public interface IntegrationEventPort {

  void publish(String type, String aggregateType, UUID aggregateId, Map<String, Object> payload);
}

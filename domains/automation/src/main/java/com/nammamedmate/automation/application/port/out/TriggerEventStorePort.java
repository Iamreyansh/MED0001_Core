package com.nammamedmate.automation.application.port.out;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface TriggerEventStorePort {

  UUID insert(
      String triggerId,
      String entityType,
      UUID entityId,
      Map<String, Object> payload,
      Instant firedAt);

  void markProcessed(
      UUID eventId, Instant processedAt, int rulesEvaluated, int rulesFired, String outcome);
}

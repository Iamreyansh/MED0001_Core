package com.nammamedmate.automation.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Historical trigger_events for batch replay + entity preview context. */
public interface TriggerEventQueryPort {

  record TriggerEventRow(
      UUID id,
      String triggerId,
      String entityType,
      UUID entityId,
      Map<String, Object> payload,
      Instant firedAt) {

    public TriggerEventRow {
      payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
  }

  List<TriggerEventRow> listRecentByTrigger(String triggerId, int limit);

  Optional<TriggerEventRow> findLatestByEntity(String entityType, UUID entityId);
}

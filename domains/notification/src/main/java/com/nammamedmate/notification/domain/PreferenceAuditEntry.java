package com.nammamedmate.notification.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record PreferenceAuditEntry(
    UUID id,
    PreferenceEntityType entityType,
    UUID entityId,
    UUID changedBy,
    PreferenceChangeSource changeSource,
    Map<String, Object> oldValues,
    Map<String, Object> newValues,
    Instant changedAt) {

  public PreferenceAuditEntry {
    oldValues =
        oldValues == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(oldValues));
    newValues =
        newValues == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(newValues));
  }
}

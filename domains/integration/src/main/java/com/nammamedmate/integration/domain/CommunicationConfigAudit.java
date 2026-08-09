package com.nammamedmate.integration.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record CommunicationConfigAudit(
    UUID id,
    String channel,
    UUID changedBy,
    Map<String, Object> changedFields,
    String connectivityTestResult,
    Instant changedAt) {

  public CommunicationConfigAudit {
    changedFields =
        changedFields == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(changedFields));
  }
}

package com.nammamedmate.integration.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record GovernmentVerificationCacheEntry(
    UUID id,
    String verificationType,
    String identifier,
    String state,
    Map<String, Object> resultJson,
    boolean valid,
    LocalDate expiryDate,
    Instant verifiedAt,
    Instant expiresAt) {
  public GovernmentVerificationCacheEntry {
    resultJson = Collections.unmodifiableMap(new LinkedHashMap<>(resultJson));
  }
}

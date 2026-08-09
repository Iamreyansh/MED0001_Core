package com.nammamedmate.integration.domain;

import java.time.Instant;
import java.util.UUID;

public record GovernmentApiCallLog(
    UUID id,
    String apiType,
    String identifier,
    Integer httpStatus,
    String resultStatus,
    int latencyMs,
    boolean wasCacheHit,
    String entityType,
    UUID entityId,
    Instant calledAt) {}

package com.nammamedmate.integration.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MapsApiCallLog(
    UUID id,
    String apiType,
    String requestSummary,
    String responseStatus,
    int latencyMs,
    boolean wasCacheHit,
    BigDecimal estimatedCostRs,
    Instant calledAt,
    String callingService) {}

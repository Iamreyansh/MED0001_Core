package com.nammamedmate.integration.domain;

import java.time.Instant;
import java.util.UUID;

public record EinvoiceApiCallLog(
    UUID id,
    String apiType,
    String requestSummary,
    Integer httpStatus,
    String responseStatus,
    int latencyMs,
    Instant calledAt) {}

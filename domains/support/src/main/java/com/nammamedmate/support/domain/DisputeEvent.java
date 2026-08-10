package com.nammamedmate.support.domain;

import java.time.Instant;
import java.util.UUID;

public record DisputeEvent(
    UUID id,
    UUID disputeId,
    String eventType,
    UUID actorId,
    String actorName,
    String notes,
    Instant createdAt) {}

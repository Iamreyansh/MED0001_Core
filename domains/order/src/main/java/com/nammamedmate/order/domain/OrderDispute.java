package com.nammamedmate.order.domain;

import java.time.Instant;
import java.util.UUID;

public record OrderDispute(
    UUID id,
    UUID orderId,
    String reason,
    LiableParty liableParty,
    UUID flaggedBy,
    Instant flaggedAt,
    boolean resolved,
    Instant resolvedAt,
    String resolutionNotes) {}

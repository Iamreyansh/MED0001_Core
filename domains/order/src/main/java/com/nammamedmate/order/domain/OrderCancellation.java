package com.nammamedmate.order.domain;

import java.time.Instant;
import java.util.UUID;

public record OrderCancellation(
    UUID id,
    UUID orderId,
    CancelledByType cancelledByType,
    UUID cancelledById,
    String reason,
    Instant cancelledAt) {}

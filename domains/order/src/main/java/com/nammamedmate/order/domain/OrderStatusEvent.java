package com.nammamedmate.order.domain;

import java.time.Instant;
import java.util.UUID;

public record OrderStatusEvent(
    UUID id,
    UUID orderId,
    OrderStatus fromStatus,
    OrderStatus toStatus,
    ActorType actorType,
    UUID actorId,
    String notes,
    Instant createdAt) {}

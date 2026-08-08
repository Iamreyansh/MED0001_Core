package com.nammamedmate.order.domain;

import java.time.Instant;
import java.util.UUID;

public record OrderNote(
    UUID id, UUID orderId, String note, boolean pinned, UUID addedBy, Instant createdAt) {}

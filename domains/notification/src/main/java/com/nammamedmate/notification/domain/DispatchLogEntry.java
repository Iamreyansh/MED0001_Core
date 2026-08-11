package com.nammamedmate.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record DispatchLogEntry(
    UUID dispatchId,
    UUID recipientId,
    String recipientType,
    String channel,
    String type,
    String title,
    String status,
    Instant sentAt,
    Instant deliveredAt) {}

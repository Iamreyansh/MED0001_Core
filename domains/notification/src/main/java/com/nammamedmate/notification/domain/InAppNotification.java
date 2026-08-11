package com.nammamedmate.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record InAppNotification(
    UUID id,
    UUID customerId,
    InAppNotificationType type,
    String title,
    String body,
    String actionUrl,
    boolean read,
    boolean deleted,
    Instant readAt,
    Instant expiresAt,
    Instant createdAt) {}

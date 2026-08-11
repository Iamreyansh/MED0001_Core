package com.nammamedmate.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record PushNotificationLog(
    UUID id,
    UUID broadcastId,
    UUID recipientUserId,
    NotificationUserType recipientType,
    UUID deviceTokenId,
    String title,
    String body,
    PushPriority priority,
    String fcmMessageId,
    PushLogStatus status,
    Instant sentAt,
    Instant deliveredAt,
    Instant openedAt,
    String errorMessage) {}

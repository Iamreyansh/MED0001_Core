package com.nammamedmate.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record EmailDeliveryLog(
    UUID id,
    String toEmail,
    String toName,
    String templateId,
    String subject,
    EmailProvider provider,
    boolean fallbackUsed,
    String providerMessageId,
    EmailLogStatus status,
    Instant sentAt,
    Instant deliveredAt,
    Instant openedAt,
    Instant clickedAt,
    EmailBounceType bounceType,
    String errorMessage) {}

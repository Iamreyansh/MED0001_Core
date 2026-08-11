package com.nammamedmate.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record EmailTemplate(
    String templateId,
    String name,
    String subject,
    String htmlBody,
    String textBody,
    EmailCategory category,
    boolean active,
    int version,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt) {}

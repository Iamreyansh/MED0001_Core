package com.nammamedmate.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record SmsTemplate(
    String templateId,
    String content,
    SmsCategory category,
    String dltTemplateId,
    String senderId,
    boolean active,
    UUID createdBy,
    Instant createdAt) {}

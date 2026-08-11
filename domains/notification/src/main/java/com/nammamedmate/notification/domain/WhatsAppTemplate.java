package com.nammamedmate.notification.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record WhatsAppTemplate(
    UUID id,
    String templateName,
    WhatsAppCategory category,
    String language,
    WhatsAppTemplateStatus status,
    String bodyText,
    Map<String, Object> header,
    String footerText,
    List<Map<String, Object>> buttons,
    String metaTemplateId,
    String rejectionReason,
    Instant submittedAt,
    Instant approvedAt,
    Instant lastUsedAt) {

  public WhatsAppTemplate {
    header = header == null ? null : Collections.unmodifiableMap(header);
    buttons = buttons == null ? List.of() : List.copyOf(buttons);
  }
}

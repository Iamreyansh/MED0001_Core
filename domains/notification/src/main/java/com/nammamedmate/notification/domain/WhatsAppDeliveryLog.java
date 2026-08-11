package com.nammamedmate.notification.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record WhatsAppDeliveryLog(
    UUID id,
    String toPhone,
    String templateName,
    List<Map<String, Object>> components,
    String waMessageId,
    WhatsAppLogStatus status,
    BigDecimal costRs,
    Instant sentAt,
    Instant deliveredAt,
    Instant readAt,
    String errorCode,
    String errorMessage) {

  public WhatsAppDeliveryLog {
    components =
        components == null ? List.of() : Collections.unmodifiableList(List.copyOf(components));
  }
}

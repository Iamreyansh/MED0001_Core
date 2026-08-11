package com.nammamedmate.notification.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record SmsDeliveryLog(
    UUID id,
    String toPhone,
    String templateId,
    Map<String, String> variables,
    SmsProvider provider,
    String providerMessageId,
    boolean fallbackUsed,
    SmsLogStatus status,
    BigDecimal costRs,
    Instant sentAt,
    Instant deliveredAt,
    String errorMessage) {

  public SmsDeliveryLog {
    variables =
        variables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
  }
}

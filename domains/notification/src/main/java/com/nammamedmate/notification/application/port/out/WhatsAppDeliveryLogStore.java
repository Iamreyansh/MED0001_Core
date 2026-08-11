package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.WhatsAppDeliveryLog;
import com.nammamedmate.notification.domain.WhatsAppLogStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WhatsAppDeliveryLogStore {

  void insert(WhatsAppDeliveryLog log);

  Optional<WhatsAppDeliveryLog> findById(UUID id);

  Optional<WhatsAppDeliveryLog> findByWaMessageId(String waMessageId);

  boolean markDelivered(String waMessageId, Instant deliveredAt);

  boolean markRead(String waMessageId, Instant readAt);

  boolean markFailed(String waMessageId, String errorCode, String errorMessage);

  record ListFilter(
      String toPhone,
      String templateName,
      WhatsAppLogStatus status,
      Instant dateFrom,
      Instant dateTo,
      int page,
      int limit) {}

  record Page(List<WhatsAppDeliveryLog> logs, long total) {
    public Page {
      logs = logs == null ? List.of() : List.copyOf(logs);
    }
  }

  Page list(ListFilter filter);
}

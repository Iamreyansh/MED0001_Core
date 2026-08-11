package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.EmailBounceType;
import com.nammamedmate.notification.domain.EmailDeliveryLog;
import com.nammamedmate.notification.domain.EmailLogStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailDeliveryLogStore {

  record ListFilter(
      String toEmail,
      String templateId,
      EmailLogStatus status,
      Instant dateFrom,
      Instant dateTo,
      int page,
      int limit) {}

  record Page(List<EmailDeliveryLog> logs, long total) {
    public Page {
      logs = logs == null ? List.of() : List.copyOf(logs);
    }
  }

  record TemplateStats(Instant lastSent, long sentCount, long openedCount, long clickedCount) {}

  void insert(EmailDeliveryLog log);

  Optional<EmailDeliveryLog> findById(UUID id);

  Optional<EmailDeliveryLog> findByProviderMessageId(String providerMessageId);

  boolean markDelivered(String providerMessageId, Instant at);

  boolean markOpened(UUID logId, Instant at);

  boolean markClicked(UUID logId, Instant at);

  boolean markBounced(String providerMessageId, EmailBounceType bounceType, Instant at);

  boolean markSpam(String providerMessageId, Instant at);

  Page list(ListFilter filter);

  TemplateStats statsForTemplate(String templateId);
}

package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.SmsDeliveryLog;
import com.nammamedmate.notification.domain.SmsLogStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SmsDeliveryLogStore {

  void insert(SmsDeliveryLog log);

  Optional<SmsDeliveryLog> findById(UUID id);

  Optional<SmsDeliveryLog> findByProviderMessageId(String providerMessageId);

  boolean markDelivered(String providerMessageId, Instant deliveredAt);

  record ListFilter(
      String toPhone,
      String templateId,
      SmsLogStatus status,
      Instant dateFrom,
      Instant dateTo,
      int page,
      int limit) {}

  record Page(List<SmsDeliveryLog> logs, long total) {
    public Page {
      logs = logs == null ? List.of() : List.copyOf(logs);
    }
  }

  Page list(ListFilter filter);

  BigDecimal sumCostBetween(Instant fromInclusive, Instant toExclusive);
}

package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.InAppNotification;
import com.nammamedmate.notification.domain.InAppNotificationType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InAppNotificationStore {

  void insert(InAppNotification notification);

  Optional<InAppNotification> findByIdForCustomer(UUID id, UUID customerId);

  Page list(ListFilter filter);

  long countUnread(UUID customerId, Instant now);

  boolean markRead(UUID id, UUID customerId, Instant readAt);

  int markAllRead(UUID customerId, Instant readAt, Instant now);

  boolean softDelete(UUID id, UUID customerId);

  int softDeleteExpired(Instant now);

  int hardDeletePastRetention(Instant cutoff);

  record ListFilter(
      UUID customerId,
      boolean unreadOnly,
      InAppNotificationType type,
      Instant now,
      int page,
      int limit) {}

  record Page(List<InAppNotification> items, long total) {}
}

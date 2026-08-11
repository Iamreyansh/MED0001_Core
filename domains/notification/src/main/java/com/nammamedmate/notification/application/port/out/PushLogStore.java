package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.NotificationUserType;
import com.nammamedmate.notification.domain.PushLogStatus;
import com.nammamedmate.notification.domain.PushNotificationLog;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PushLogStore {

  void insert(PushNotificationLog log);

  Optional<PushNotificationLog> findById(UUID id);

  boolean markOpened(UUID logId, UUID recipientUserId, Instant openedAt);

  record ListFilter(
      NotificationUserType recipientType,
      PushLogStatus status,
      Instant dateFrom,
      Instant dateTo,
      int page,
      int limit) {}

  record Page(List<PushNotificationLog> logs, long total) {
    public Page {
      logs = logs == null ? List.of() : List.copyOf(logs);
    }
  }

  Page list(ListFilter filter);
}

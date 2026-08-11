package com.nammamedmate.notification.application.port.out;

import com.nammamedmate.notification.domain.DispatchLogEntry;
import java.time.Instant;
import java.util.List;

public interface DispatchLogStore {

  Page list(ListFilter filter);

  record ListFilter(
      String channel,
      String status,
      String recipientType,
      Instant dateFrom,
      Instant dateTo,
      int page,
      int limit) {}

  record Page(List<DispatchLogEntry> items, long total) {}
}

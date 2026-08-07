package com.nammamedmate.pharmacy.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PharmacyNoticeStore {

  record NoticeRow(
      UUID id,
      UUID pharmacyId,
      List<String> channels,
      String subject,
      String message,
      String templateName,
      String priority,
      UUID sentBy,
      Instant sentAt,
      UUID bulkJobId) {
    public NoticeRow {
      if (channels != null) {
        channels = List.copyOf(channels);
      }
    }
  }

  void insert(NoticeRow row);

  int countSince(UUID pharmacyId, Instant since);

  Instant oldestSentAtSince(UUID pharmacyId, Instant since);
}

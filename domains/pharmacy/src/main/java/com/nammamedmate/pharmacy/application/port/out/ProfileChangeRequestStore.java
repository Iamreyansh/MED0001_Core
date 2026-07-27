package com.nammamedmate.pharmacy.application.port.out;

import java.time.Instant;
import java.util.UUID;

public interface ProfileChangeRequestStore {

  record ChangeRequestRecord(
      UUID id,
      UUID pharmacyId,
      String fieldName,
      String oldValue,
      String newValue,
      String status,
      UUID reviewedBy,
      Instant reviewedAt,
      Instant createdAt) {}

  void insert(ChangeRequestRecord record);
}

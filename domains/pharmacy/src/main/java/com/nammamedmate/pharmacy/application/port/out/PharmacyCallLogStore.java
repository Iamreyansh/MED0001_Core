package com.nammamedmate.pharmacy.application.port.out;

import java.time.Instant;
import java.util.UUID;

public interface PharmacyCallLogStore {

  record CallLogRow(
      UUID id,
      UUID pharmacyId,
      int durationSeconds,
      String callOutcome,
      String notes,
      UUID loggedBy,
      Instant loggedAt) {}

  void insert(CallLogRow row);
}

package com.nammamedmate.medicine_schedule.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public interface RefillLogStore {

  void insert(RefillLogRecord log);

  /** True if a nightly (negative) decrement was already logged for this medicine+date. */
  boolean existsNegativeOnDate(UUID medicineId, LocalDate refillDate);

  record RefillLogRecord(
      UUID id,
      UUID medicineId,
      UUID customerId,
      int unitsAdded,
      int unitsBefore,
      int unitsAfter,
      LocalDate refillDate,
      Instant createdAt) {}
}

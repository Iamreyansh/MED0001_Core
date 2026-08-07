package com.nammamedmate.pharmacy.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PerformanceAlertStore {

  record AlertRow(
      UUID id,
      UUID pharmacyId,
      String alertType,
      UUID triggeredBy,
      BigDecimal thresholdValue,
      String message,
      List<String> channels,
      Instant sentAt) {
    public AlertRow {
      channels = channels == null ? List.of() : List.copyOf(channels);
    }
  }

  void insert(AlertRow row);

  /** Most recent alert of the given type within the throttle window, if any. */
  Optional<Instant> lastSentAt(UUID pharmacyId, String alertType, Instant since);
}

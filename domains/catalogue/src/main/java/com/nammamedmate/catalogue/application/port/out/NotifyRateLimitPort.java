package com.nammamedmate.catalogue.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface NotifyRateLimitPort {

  /**
   * Attempts to acquire a notify batch slot for the medicine (or global ALL when medicineId is
   * null). Returns empty when acquired; otherwise the Instant when the next batch is allowed.
   */
  Optional<Instant> tryAcquire(UUID medicineId, Duration window, Instant now);
}

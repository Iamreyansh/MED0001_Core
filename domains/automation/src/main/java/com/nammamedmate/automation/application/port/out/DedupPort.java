package com.nammamedmate.automation.application.port.out;

import java.time.Duration;
import java.util.UUID;

public interface DedupPort {

  /** Returns true if this (rule, entity) was already fired within the window. */
  boolean isDuplicate(UUID ruleId, UUID entityId, Duration window);

  void recordFire(UUID ruleId, UUID entityId);
}

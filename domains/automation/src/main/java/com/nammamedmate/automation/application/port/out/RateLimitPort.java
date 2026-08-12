package com.nammamedmate.automation.application.port.out;

import java.util.UUID;

/**
 * Global per-rule fire rate limit (guardrails.rate_limit).
 *
 * <p>ponytail: in-memory sliding window (single JVM). Ceiling: multi-instance / Lambda concurrency
 * will under-count. Upgrade: Redis sliding-window ZSET or INCR+TTL.
 */
public interface RateLimitPort {

  /**
   * @return true if the fire is allowed (and recorded); false if rate limited
   */
  boolean tryAcquire(UUID ruleId, int maxFires, int perMinutes);

  /** Peek without recording a fire (simulation-preview). */
  default boolean wouldExceed(UUID ruleId, int maxFires, int perMinutes) {
    return false;
  }
}

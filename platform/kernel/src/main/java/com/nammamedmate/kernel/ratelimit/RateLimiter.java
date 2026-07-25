package com.nammamedmate.kernel.ratelimit;

public interface RateLimiter {

  /**
   * @return true if the request is allowed
   */
  boolean tryAcquire(String key, int limit, int windowSeconds);

  /**
   * Seconds until {@link #tryAcquire} could succeed for the same key/limit/window. Returns 0 when
   * under the limit.
   */
  int secondsUntilAvailable(String key, int limit, int windowSeconds);

  /** Blocks {@code key} for {@code ttlSeconds} (cooldown). */
  void putCooldown(String key, int ttlSeconds);

  /** Remaining cooldown seconds for {@code key}, or 0 if not cooling down. */
  int cooldownRemainingSeconds(String key);
}

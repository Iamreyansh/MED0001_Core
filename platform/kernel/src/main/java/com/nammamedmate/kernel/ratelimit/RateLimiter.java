package com.nammamedmate.kernel.ratelimit;

public interface RateLimiter {

  /**
   * @return true if the request is allowed
   */
  boolean tryAcquire(String key, int limit, int windowSeconds);
}

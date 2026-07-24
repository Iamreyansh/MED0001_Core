package com.nammamedmate.kernel.ratelimit;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Local token-bucket style limiter for tests and local boot. Prefer Redis in deployed envs. */
public final class InMemoryRateLimiter implements RateLimiter {

  private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
  private final Clock clock;

  public InMemoryRateLimiter(Clock clock) {
    this.clock = clock;
  }

  public InMemoryRateLimiter() {
    this(Clock.systemUTC());
  }

  @Override
  public boolean tryAcquire(String key, int limit, int windowSeconds) {
    if (key == null || key.isBlank() || limit < 1 || windowSeconds < 1) {
      return false;
    }
    long now = clock.millis();
    long cutoff = now - (windowSeconds * 1000L);
    Deque<Long> q = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (q) {
      while (!q.isEmpty() && q.peekFirst() < cutoff) {
        q.removeFirst();
      }
      if (q.size() >= limit) {
        return false;
      }
      q.addLast(now);
      return true;
    }
  }
}

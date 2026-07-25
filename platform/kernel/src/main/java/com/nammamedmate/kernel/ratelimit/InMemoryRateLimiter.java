package com.nammamedmate.kernel.ratelimit;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Local token-bucket style limiter for tests and local boot. Prefer Redis in deployed envs. */
public final class InMemoryRateLimiter implements RateLimiter {

  private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
  private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
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

  @Override
  public int secondsUntilAvailable(String key, int limit, int windowSeconds) {
    if (key == null || key.isBlank() || limit < 1 || windowSeconds < 1) {
      return 0;
    }
    long now = clock.millis();
    long cutoff = now - (windowSeconds * 1000L);
    Deque<Long> q = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (q) {
      while (!q.isEmpty() && q.peekFirst() < cutoff) {
        q.removeFirst();
      }
      if (q.size() < limit) {
        return 0;
      }
      long oldest = q.peekFirst();
      long retryAt = oldest + (windowSeconds * 1000L);
      long remainingMs = retryAt - now;
      if (remainingMs <= 0) {
        return 0;
      }
      return (int) ((remainingMs + 999) / 1000);
    }
  }

  @Override
  public void putCooldown(String key, int ttlSeconds) {
    if (key == null || key.isBlank() || ttlSeconds < 1) {
      return;
    }
    cooldowns.put(key, clock.millis() + (ttlSeconds * 1000L));
  }

  @Override
  public int cooldownRemainingSeconds(String key) {
    if (key == null || key.isBlank()) {
      return 0;
    }
    Long expiresAt = cooldowns.get(key);
    if (expiresAt == null) {
      return 0;
    }
    long remainingMs = expiresAt - clock.millis();
    if (remainingMs <= 0) {
      cooldowns.remove(key);
      return 0;
    }
    return (int) ((remainingMs + 999) / 1000);
  }
}

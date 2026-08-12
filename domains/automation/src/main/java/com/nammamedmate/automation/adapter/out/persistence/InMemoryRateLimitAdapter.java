package com.nammamedmate.automation.adapter.out.persistence;

import com.nammamedmate.automation.application.port.out.RateLimitPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * ponytail: in-memory sliding window (single JVM). Ceiling: multi-instance / Lambda concurrency
 * under-counts. Upgrade: Redis ZSET sliding window keyed by rule_id.
 */
@Component
public class InMemoryRateLimitAdapter implements RateLimitPort {

  private final Clock clock;
  private final Map<UUID, Deque<Instant>> windows = new ConcurrentHashMap<>();

  public InMemoryRateLimitAdapter(Clock clock) {
    this.clock = clock;
  }

  @Override
  public boolean tryAcquire(UUID ruleId, int maxFires, int perMinutes) {
    if (ruleId == null || maxFires <= 0 || perMinutes <= 0) {
      return true;
    }
    Instant now = clock.instant();
    Instant cutoff = now.minus(Duration.ofMinutes(perMinutes));
    Deque<Instant> q = windows.computeIfAbsent(ruleId, k -> new ArrayDeque<>());
    synchronized (q) {
      while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
        q.removeFirst();
      }
      if (q.size() >= maxFires) {
        return false;
      }
      q.addLast(now);
      return true;
    }
  }

  @Override
  public boolean wouldExceed(UUID ruleId, int maxFires, int perMinutes) {
    if (ruleId == null || maxFires <= 0 || perMinutes <= 0) {
      return false;
    }
    Instant now = clock.instant();
    Instant cutoff = now.minus(Duration.ofMinutes(perMinutes));
    Deque<Instant> q = windows.get(ruleId);
    if (q == null) {
      return false;
    }
    synchronized (q) {
      while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
        q.removeFirst();
      }
      return q.size() >= maxFires;
    }
  }
}

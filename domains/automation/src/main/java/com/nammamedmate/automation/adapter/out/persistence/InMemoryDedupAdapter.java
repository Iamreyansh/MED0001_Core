package com.nammamedmate.automation.adapter.out.persistence;

import com.nammamedmate.automation.application.port.out.DedupPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryDedupAdapter implements DedupPort {

  private final Map<String, Instant> lastFire = new ConcurrentHashMap<>();
  private final Clock clock;

  public InMemoryDedupAdapter(Clock clock) {
    this.clock = clock;
  }

  @Override
  public boolean isDuplicate(UUID ruleId, UUID entityId, Duration window) {
    Instant prev = lastFire.get(key(ruleId, entityId));
    if (prev == null) {
      return false;
    }
    return Duration.between(prev, clock.instant()).compareTo(window) < 0;
  }

  @Override
  public void recordFire(UUID ruleId, UUID entityId) {
    lastFire.put(key(ruleId, entityId), clock.instant());
  }

  private static String key(UUID ruleId, UUID entityId) {
    return ruleId + ":" + entityId;
  }
}

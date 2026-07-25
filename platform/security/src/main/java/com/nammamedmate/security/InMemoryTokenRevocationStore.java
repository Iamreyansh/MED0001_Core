package com.nammamedmate.security;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryTokenRevocationStore implements TokenRevocationStore {

  private final Map<String, Long> revokedUntil = new ConcurrentHashMap<>();
  private final Clock clock;

  public InMemoryTokenRevocationStore(Clock clock) {
    this.clock = clock;
  }

  public InMemoryTokenRevocationStore() {
    this(Clock.systemUTC());
  }

  @Override
  public boolean isRevoked(String jti) {
    if (jti == null || jti.isBlank()) {
      return true;
    }
    Long until = revokedUntil.get(jti);
    if (until == null) {
      return false;
    }
    if (until < clock.millis()) {
      revokedUntil.remove(jti);
      return false;
    }
    return true;
  }

  @Override
  public void revoke(String jti, long ttlSeconds) {
    if (jti == null || jti.isBlank() || ttlSeconds < 1) {
      return;
    }
    revokedUntil.put(jti, clock.millis() + ttlSeconds * 1000L);
  }

  @Override
  public boolean tryRevoke(String jti, long ttlSeconds) {
    if (jti == null || jti.isBlank() || ttlSeconds < 1) {
      return false;
    }
    long until = clock.millis() + ttlSeconds * 1000L;
    Long previous = revokedUntil.putIfAbsent(jti, until);
    if (previous == null) {
      return true;
    }
    if (previous < clock.millis()) {
      return revokedUntil.replace(jti, previous, until);
    }
    return false;
  }
}

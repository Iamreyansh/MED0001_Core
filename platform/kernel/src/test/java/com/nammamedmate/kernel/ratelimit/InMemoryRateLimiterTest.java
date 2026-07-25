package com.nammamedmate.kernel.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InMemoryRateLimiterTest {

  @Test
  void enforcesLimitAndWindow() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);
    assertThat(limiter.tryAcquire("k", 2, 60)).isTrue();
    assertThat(limiter.tryAcquire("k", 2, 60)).isTrue();
    assertThat(limiter.tryAcquire("k", 2, 60)).isFalse();
    assertThat(limiter.secondsUntilAvailable("k", 2, 60)).isEqualTo(60);
    clock.advanceSeconds(60);
    assertThat(limiter.secondsUntilAvailable("k", 2, 60)).isZero();
    clock.advanceMillis(1);
    assertThat(limiter.tryAcquire("k", 2, 60)).isTrue();
  }

  @Test
  void rejectsInvalidArgs() {
    InMemoryRateLimiter limiter = new InMemoryRateLimiter();
    assertThat(limiter.tryAcquire(null, 1, 1)).isFalse();
    assertThat(limiter.tryAcquire("", 1, 1)).isFalse();
    assertThat(limiter.tryAcquire(" ", 1, 1)).isFalse();
    assertThat(limiter.tryAcquire("k", 0, 1)).isFalse();
    assertThat(limiter.tryAcquire("k", -1, 1)).isFalse();
    assertThat(limiter.tryAcquire("k", 1, 0)).isFalse();
    assertThat(limiter.tryAcquire("k", 1, -1)).isFalse();
    assertThat(limiter.secondsUntilAvailable(null, 1, 1)).isZero();
    assertThat(limiter.secondsUntilAvailable("", 1, 1)).isZero();
    assertThat(limiter.secondsUntilAvailable(" ", 1, 1)).isZero();
    assertThat(limiter.secondsUntilAvailable("k", 0, 1)).isZero();
    assertThat(limiter.secondsUntilAvailable("k", -1, 1)).isZero();
    assertThat(limiter.secondsUntilAvailable("k", 1, 0)).isZero();
    assertThat(limiter.secondsUntilAvailable("k", 1, -1)).isZero();
  }

  @Test
  void cooldownLifecycle() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);
    limiter.putCooldown(null, 10);
    limiter.putCooldown("", 10);
    limiter.putCooldown(" ", 10);
    limiter.putCooldown("c", 0);
    limiter.putCooldown("c", -1);
    limiter.putCooldown("c", 30);
    assertThat(limiter.cooldownRemainingSeconds("c")).isEqualTo(30);
    assertThat(limiter.cooldownRemainingSeconds(null)).isZero();
    assertThat(limiter.cooldownRemainingSeconds("")).isZero();
    assertThat(limiter.cooldownRemainingSeconds(" ")).isZero();
    assertThat(limiter.cooldownRemainingSeconds("missing")).isZero();
    clock.advanceSeconds(29);
    assertThat(limiter.cooldownRemainingSeconds("c")).isEqualTo(1);
    clock.advanceSeconds(1);
    assertThat(limiter.cooldownRemainingSeconds("c")).isZero();
  }

  @Test
  void prunesExpiredWindowEntriesOnAcquire() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);
    assertThat(limiter.tryAcquire("p", 1, 10)).isTrue();
    clock.advanceSeconds(11);
    assertThat(limiter.tryAcquire("p", 1, 10)).isTrue();
    assertThat(limiter.secondsUntilAvailable("fresh", 1, 10)).isZero();
  }

  @Test
  void secondsUntilAvailablePrunesExpiredEntries() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);
    assertThat(limiter.tryAcquire("z", 1, 10)).isTrue();
    assertThat(limiter.secondsUntilAvailable("z", 1, 10)).isEqualTo(10);
    clock.advanceSeconds(11);
    assertThat(limiter.secondsUntilAvailable("z", 1, 10)).isZero();
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advanceSeconds(long seconds) {
      instant = instant.plusSeconds(seconds);
    }

    void advanceMillis(long millis) {
      instant = instant.plusMillis(millis);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}

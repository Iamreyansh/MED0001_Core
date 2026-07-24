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
    clock.advanceSeconds(61);
    assertThat(limiter.tryAcquire("k", 2, 60)).isTrue();
  }

  @Test
  void rejectsInvalidArgs() {
    InMemoryRateLimiter limiter = new InMemoryRateLimiter();
    assertThat(limiter.tryAcquire(null, 1, 1)).isFalse();
    assertThat(limiter.tryAcquire(" ", 1, 1)).isFalse();
    assertThat(limiter.tryAcquire("k", 0, 1)).isFalse();
    assertThat(limiter.tryAcquire("k", 1, 0)).isFalse();
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advanceSeconds(long seconds) {
      instant = instant.plusSeconds(seconds);
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

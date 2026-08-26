package com.nammamedmate.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class WorkerPlatformConfigTest {

  @Test
  void providesClockAndInMemoryRateLimiter() {
    WorkerPlatformConfig config = new WorkerPlatformConfig();
    Clock clock = config.clock();
    RateLimiter limiter = config.rateLimiter(clock);
    assertThat(clock).isNotNull();
    assertThat(limiter).isInstanceOf(InMemoryRateLimiter.class);
  }
}

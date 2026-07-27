package com.nammamedmate.api.support;

import com.nammamedmate.kernel.ratelimit.RateLimiter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the rate limiter with a no-op for integration tests so tests don't conflict. */
@TestConfiguration
public class TestRateLimiterConfig {

  @Bean
  @Primary
  RateLimiter testRateLimiter() {
    return new RateLimiter() {
      @Override
      public boolean tryAcquire(String key, int limit, int windowSeconds) {
        return true;
      }

      @Override
      public int secondsUntilAvailable(String key, int limit, int windowSeconds) {
        return 0;
      }

      @Override
      public void putCooldown(String key, int ttlSeconds) {}

      @Override
      public int cooldownRemainingSeconds(String key) {
        return 0;
      }
    };
  }
}

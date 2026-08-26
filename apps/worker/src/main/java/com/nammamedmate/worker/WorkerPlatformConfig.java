package com.nammamedmate.worker;

import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared platform beans the worker needs because it component-scans domain application services
 * (e.g. pharmacy) that take {@link RateLimiter} — same contract as API {@code PlatformConfig}.
 */
@Configuration
public class WorkerPlatformConfig {

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean(RateLimiter.class)
  RateLimiter rateLimiter(Clock clock) {
    return new InMemoryRateLimiter(clock);
  }
}

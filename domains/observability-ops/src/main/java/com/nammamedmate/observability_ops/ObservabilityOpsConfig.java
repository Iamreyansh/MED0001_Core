package com.nammamedmate.observability_ops;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityOpsConfig {

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock observabilityClock() {
    return Clock.systemUTC();
  }
}

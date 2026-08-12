package com.nammamedmate.automation;

import com.nammamedmate.automation.adapter.out.executor.StubActionExecutor;
import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.domain.ConditionEvaluator;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AutomationConfig {

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock automationClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean(ConditionEvaluator.class)
  ConditionEvaluator conditionEvaluator(Clock clock) {
    return new ConditionEvaluator(clock);
  }

  @Bean
  @ConditionalOnMissingBean(ActionExecutorPort.class)
  ActionExecutorPort stubActionExecutorPort(ActivityLogPort activityLog) {
    return new StubActionExecutor(activityLog);
  }
}

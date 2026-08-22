package com.nammamedmate.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.messaging.JdbcOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.messaging.OutboxStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class WorkerMessagingConfig {

  @Bean
  @ConditionalOnMissingBean(OutboxStore.class)
  OutboxStore outboxStore(JdbcTemplate jdbcTemplate) {
    return new JdbcOutboxStore(jdbcTemplate);
  }

  @Bean
  @ConditionalOnMissingBean(OutboxPublisher.class)
  OutboxPublisher outboxPublisher(OutboxStore store, ObjectMapper objectMapper) {
    return new OutboxPublisher(store, objectMapper);
  }
}

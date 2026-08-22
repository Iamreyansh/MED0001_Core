package com.nammamedmate.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.messaging.ConsumerInbox;
import com.nammamedmate.messaging.JdbcConsumerInbox;
import com.nammamedmate.messaging.JdbcOutboxStore;
import com.nammamedmate.messaging.JdbcProviderOperationStore;
import com.nammamedmate.messaging.JdbcWebhookInbox;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.messaging.OutboxStore;
import com.nammamedmate.messaging.ProviderOperationStore;
import com.nammamedmate.messaging.WebhookInbox;
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

  @Bean
  @ConditionalOnMissingBean(ConsumerInbox.class)
  ConsumerInbox consumerInbox(JdbcTemplate jdbcTemplate) {
    return new JdbcConsumerInbox(jdbcTemplate);
  }

  @Bean
  @ConditionalOnMissingBean(ProviderOperationStore.class)
  ProviderOperationStore providerOperationStore(JdbcTemplate jdbcTemplate) {
    return new JdbcProviderOperationStore(jdbcTemplate);
  }

  @Bean
  @ConditionalOnMissingBean(WebhookInbox.class)
  WebhookInbox webhookInbox(JdbcTemplate jdbcTemplate) {
    return new JdbcWebhookInbox(jdbcTemplate);
  }
}

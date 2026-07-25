package com.nammamedmate.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
class WorkerAwsConfig {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(SqsClient.class)
  SqsClient sqsClient() {
    return SqsClient.create();
  }
}

package com.nammamedmate.worker;

import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcKycDocumentStore;
import com.nammamedmate.pharmacy.adapter.out.storage.LocalKycObjectStore;
import com.nammamedmate.pharmacy.adapter.out.storage.S3KycObjectStore;
import com.nammamedmate.pharmacy.application.KycMalwareScanResultService;
import com.nammamedmate.pharmacy.application.port.out.KycObjectStore;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Wires KYC malware scan handling only when the GuardDuty→EventBridge→SQS queue URL is set
 * (staging/prod ECS). Keeps local worker boot free of a required Postgres.
 */
@Configuration
@ConditionalOnProperty(name = "medmate.sqs.kyc-malware-queue-url")
@ImportAutoConfiguration({
  DataSourceAutoConfiguration.class,
  DataSourceTransactionManagerAutoConfiguration.class,
  JdbcTemplateAutoConfiguration.class,
  TransactionAutoConfiguration.class
})
@Import({JdbcKycDocumentStore.class, KycMalwareScanResultService.class})
public class WorkerPharmacyConfig {

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  @Profile({"prod", "staging"})
  @ConditionalOnMissingBean(S3Client.class)
  S3Client s3Client() {
    return S3Client.create();
  }

  @Bean
  @Profile({"prod", "staging"})
  KycObjectStore s3KycObjectStore(S3Client s3, @Value("${medmate.s3.bucket}") String bucket) {
    return new S3KycObjectStore(s3, bucket);
  }

  @Bean
  @Profile("!prod & !staging")
  @ConditionalOnMissingBean(KycObjectStore.class)
  KycObjectStore localKycObjectStore() {
    return new LocalKycObjectStore();
  }
}

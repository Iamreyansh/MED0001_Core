package com.nammamedmate.notification;

import com.nammamedmate.notification.adapter.out.client.HttpVendorClients;
import com.nammamedmate.notification.adapter.out.client.StubAttachmentFetcher;
import com.nammamedmate.notification.adapter.out.client.StubFcmClient;
import com.nammamedmate.notification.adapter.out.client.StubTwilioClient;
import com.nammamedmate.notification.application.port.out.AttachmentFetcherPort;
import com.nammamedmate.notification.application.port.out.FcmClientPort;
import com.nammamedmate.notification.application.port.out.RecipientDisplayNamePort;
import com.nammamedmate.notification.application.port.out.TwilioClientPort;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class NotificationConfig {

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock notificationClock() {
    return Clock.systemUTC();
  }

  @Bean
  @Profile("!prod & !staging")
  @ConditionalOnMissingBean(FcmClientPort.class)
  FcmClientPort stubFcmClient() {
    return new StubFcmClient();
  }

  @Bean
  @Profile("!prod & !staging")
  @ConditionalOnMissingBean(TwilioClientPort.class)
  TwilioClientPort stubTwilioClient() {
    return new StubTwilioClient();
  }

  @Bean
  @Profile({"prod", "staging"})
  @ConditionalOnMissingBean(FcmClientPort.class)
  FcmClientPort httpFcmClient(
      @Value("${medmate.fcm.project-id:}") String projectId,
      @Value("${medmate.fcm.service-account-json:}") String serviceAccountJson) {
    return new HttpVendorClients.HttpFcmClient(projectId, serviceAccountJson);
  }

  @Bean
  @Profile({"prod", "staging"})
  @ConditionalOnMissingBean(TwilioClientPort.class)
  TwilioClientPort httpTwilioClient(
      @Value("${medmate.twilio.account-sid:}") String sid,
      @Value("${medmate.twilio.auth-token:}") String token,
      @Value("${medmate.twilio.api-key:}") String apiKey) {
    return new HttpVendorClients.HttpTwilioClient(
        sid, token, apiKey, HttpVendorClients.jdkPoster());
  }

  @Bean
  @ConditionalOnMissingBean(AttachmentFetcherPort.class)
  AttachmentFetcherPort stubAttachmentFetcher() {
    return new StubAttachmentFetcher();
  }

  @Bean
  @ConditionalOnMissingBean(RecipientDisplayNamePort.class)
  RecipientDisplayNamePort stubRecipientDisplayName() {
    return (userId, userType) -> userId == null ? Optional.empty() : Optional.of("Recipient");
  }
}

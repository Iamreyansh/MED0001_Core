package com.nammamedmate.notification;

import com.nammamedmate.notification.adapter.out.client.StubAttachmentFetcher;
import com.nammamedmate.notification.adapter.out.client.StubFcmClient;
import com.nammamedmate.notification.adapter.out.client.StubMetaWhatsAppClient;
import com.nammamedmate.notification.adapter.out.client.StubMsg91Client;
import com.nammamedmate.notification.adapter.out.client.StubSendGridClient;
import com.nammamedmate.notification.adapter.out.client.StubSesClient;
import com.nammamedmate.notification.adapter.out.client.StubTwilioClient;
import com.nammamedmate.notification.application.port.out.AttachmentFetcherPort;
import com.nammamedmate.notification.application.port.out.CommunicationChannelLookupPort;
import com.nammamedmate.notification.application.port.out.FcmClientPort;
import com.nammamedmate.notification.application.port.out.MetaWhatsAppClientPort;
import com.nammamedmate.notification.application.port.out.Msg91ClientPort;
import com.nammamedmate.notification.application.port.out.RecipientDisplayNamePort;
import com.nammamedmate.notification.application.port.out.SendGridClientPort;
import com.nammamedmate.notification.application.port.out.SesClientPort;
import com.nammamedmate.notification.application.port.out.TwilioClientPort;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
  @ConditionalOnMissingBean(FcmClientPort.class)
  FcmClientPort stubFcmClient() {
    return new StubFcmClient();
  }

  @Bean
  @ConditionalOnMissingBean(Msg91ClientPort.class)
  Msg91ClientPort stubMsg91Client() {
    return new StubMsg91Client();
  }

  @Bean
  @ConditionalOnMissingBean(TwilioClientPort.class)
  TwilioClientPort stubTwilioClient() {
    return new StubTwilioClient();
  }

  @Bean
  @ConditionalOnMissingBean(MetaWhatsAppClientPort.class)
  MetaWhatsAppClientPort stubMetaWhatsAppClient(
      @Value("${medmate.whatsapp.app-secret:}") String appSecret) {
    return new StubMetaWhatsAppClient(
        appSecret == null || appSecret.isBlank()
            ? StubMetaWhatsAppClient.DEFAULT_APP_SECRET
            : appSecret);
  }

  @Bean
  @ConditionalOnMissingBean(SendGridClientPort.class)
  SendGridClientPort stubSendGridClient() {
    return new StubSendGridClient();
  }

  @Bean
  @ConditionalOnMissingBean(SesClientPort.class)
  SesClientPort stubSesClient() {
    return new StubSesClient();
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

  @Bean
  @ConditionalOnMissingBean(CommunicationChannelLookupPort.class)
  CommunicationChannelLookupPort alwaysHealthyChannelLookup() {
    return channel -> Optional.of("MSG91");
  }
}

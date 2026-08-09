package com.nammamedmate.api.config;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pos.application.port.out.PosNotificationPort;
import com.nammamedmate.pos.domain.ShareChannel;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Composition-root: invoice share + khata reminders via outbox (ids-only). When notifications
 * disabled → CHANNEL_UNAVAILABLE.
 */
@Configuration
public class PosNotificationBridgeConfig {

  @Bean
  @Primary
  PosNotificationPort posNotificationPort(
      Optional<OutboxPublisher> outbox,
      @Value("${medmate.pos.notifications-enabled:true}") boolean notificationsEnabled) {
    return new PosNotificationPort() {
      @Override
      public ShareResult shareInvoice(
          UUID pharmacyId,
          UUID invoiceId,
          String invoiceNumber,
          ShareChannel channel,
          String recipient,
          String pdfUrl) {
        return dispatch(
            notificationsEnabled,
            outbox,
            "pos.invoice.shared",
            "invoice",
            invoiceId,
            Map.of(
                "pharmacy_id",
                pharmacyId.toString(),
                "invoice_id",
                invoiceId.toString(),
                "channel",
                channel.name()),
            channel);
      }

      @Override
      public ShareResult sendKhataReminder(
          UUID pharmacyId,
          UUID customerId,
          ShareChannel channel,
          String template,
          String recipient,
          long outstandingPaise) {
        return dispatch(
            notificationsEnabled,
            outbox,
            "pos.khata.reminder",
            "customer",
            customerId,
            Map.of(
                "pharmacy_id",
                pharmacyId.toString(),
                "customer_id",
                customerId.toString(),
                "channel",
                channel.name(),
                "template",
                template),
            channel);
      }
    };
  }

  private static PosNotificationPort.ShareResult dispatch(
      boolean notificationsEnabled,
      Optional<OutboxPublisher> outbox,
      String eventType,
      String aggregateType,
      UUID aggregateId,
      Map<String, Object> payload,
      ShareChannel channel) {
    if (!notificationsEnabled) {
      throw new AppException(
          "CHANNEL_UNAVAILABLE", "Notification channel temporarily unavailable", 503);
    }
    Instant now = Instant.now();
    String messageId = channel.name().toLowerCase(Locale.ROOT) + "_msg_" + Ids.newId();
    Map<String, Object> body = new java.util.LinkedHashMap<>(payload);
    body.put("message_id", messageId);
    outbox.ifPresent(
        publisher ->
            publisher.publish(DomainEvent.of(eventType, aggregateType, aggregateId, body)));
    return new PosNotificationPort.ShareResult(messageId, now);
  }
}

package com.nammamedmate.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.customer.adapter.in.messaging.OrderDeliveredLoyaltyConsumer;
import com.nammamedmate.customer.adapter.in.messaging.OrderDeliveredReferralConsumer;
import com.nammamedmate.marketing.adapter.in.messaging.OrderDeliveredCampaignConsumer;
import com.nammamedmate.messaging.OutboxMessage;
import com.nammamedmate.notification.adapter.in.messaging.CustomerNotificationRequestedHandler;
import com.nammamedmate.pharmacy.adapter.in.messaging.AutoKycOutboxConsumer;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Routes SQS domain-event payloads to idempotent consumers. Failures propagate for retry/DLQ. */
@Component
public class DomainEventRouter {

  private static final Logger log = LoggerFactory.getLogger(DomainEventRouter.class);

  private final ObjectMapper objectMapper;
  private final ObjectProvider<CustomerNotificationRequestedHandler> notifications;
  private final ObjectProvider<AutoKycOutboxConsumer> autoKyc;
  private final ObjectProvider<OrderDeliveredLoyaltyConsumer> loyalty;
  private final ObjectProvider<OrderDeliveredReferralConsumer> referral;
  private final ObjectProvider<OrderDeliveredCampaignConsumer> campaigns;

  public DomainEventRouter(
      ObjectMapper objectMapper,
      ObjectProvider<CustomerNotificationRequestedHandler> notifications,
      ObjectProvider<AutoKycOutboxConsumer> autoKyc,
      ObjectProvider<OrderDeliveredLoyaltyConsumer> loyalty,
      ObjectProvider<OrderDeliveredReferralConsumer> referral,
      ObjectProvider<OrderDeliveredCampaignConsumer> campaigns) {
    this.objectMapper = objectMapper;
    this.notifications = notifications;
    this.autoKyc = autoKyc;
    this.loyalty = loyalty;
    this.referral = referral;
    this.campaigns = campaigns;
  }

  public void handle(String messageBody) {
    if (messageBody == null || messageBody.isBlank()) {
      log.warn("Received empty SQS payload — ack without requeue");
      return;
    }
    Map<String, Object> root;
    try {
      root = objectMapper.readValue(messageBody, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse domain event", e);
    }
    String type = root.get("type") == null ? "" : String.valueOf(root.get("type"));
    OutboxMessage envelope =
        new OutboxMessage(eventId(root), type, messageBody, Instant.now(), false);
    switch (type) {
      case "customer.notification.requested" ->
          require(notifications.getIfAvailable(), type).handleMessage(messageBody);
      case "pharmacy.kyc.auto_verify_requested", "pharmacy.kyc.async_check_requested" ->
          require(autoKyc.getIfAvailable(), type).accept(envelope);
      case "order.delivered" -> {
        Optional.ofNullable(loyalty.getIfAvailable()).ifPresent(c -> c.accept(envelope));
        Optional.ofNullable(referral.getIfAvailable()).ifPresent(c -> c.accept(envelope));
        Optional.ofNullable(campaigns.getIfAvailable()).ifPresent(c -> c.accept(envelope));
        if (loyalty.getIfAvailable() == null
            && referral.getIfAvailable() == null
            && campaigns.getIfAvailable() == null) {
          throw new IllegalStateException("No consumer for " + type);
        }
      }
      default ->
          log.info("No dedicated consumer for type={} length={}", type, messageBody.length());
    }
  }

  private static <T> T require(T bean, String type) {
    if (bean == null) {
      throw new IllegalStateException("No consumer for " + type);
    }
    return bean;
  }

  private static UUID eventId(Map<String, Object> root) {
    Object raw = root.get("eventId");
    if (raw == null) {
      raw = root.get("event_id");
    }
    try {
      return raw == null ? UUID.randomUUID() : UUID.fromString(String.valueOf(raw));
    } catch (IllegalArgumentException e) {
      return UUID.randomUUID();
    }
  }
}

package com.nammamedmate.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.adapter.in.messaging.AutomationTriggerConsumer;
import com.nammamedmate.customer.adapter.in.messaging.OrderDeliveredLoyaltyConsumer;
import com.nammamedmate.customer.adapter.in.messaging.OrderDeliveredReferralConsumer;
import com.nammamedmate.marketing.adapter.in.messaging.OrderDeliveredCampaignConsumer;
import com.nammamedmate.messaging.ConsumerInbox;
import com.nammamedmate.messaging.OutboxMessage;
import com.nammamedmate.notification.adapter.in.messaging.CustomerNotificationRequestedHandler;
import com.nammamedmate.notification.adapter.in.messaging.NotificationDispatchConsumer;
import com.nammamedmate.pharmacy.adapter.in.messaging.AutoKycOutboxConsumer;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Routes SQS domain-event payloads to idempotent consumers. Failures propagate for retry/DLQ. */
@Component
public class DomainEventRouter {

  private static final Logger log = LoggerFactory.getLogger(DomainEventRouter.class);

  private final ObjectMapper objectMapper;
  private final ObjectProvider<CustomerNotificationRequestedHandler> notifications;
  private final ObjectProvider<NotificationDispatchConsumer> dispatch;
  private final ObjectProvider<AutoKycOutboxConsumer> autoKyc;
  private final ObjectProvider<OrderDeliveredLoyaltyConsumer> loyalty;
  private final ObjectProvider<OrderDeliveredReferralConsumer> referral;
  private final ObjectProvider<OrderDeliveredCampaignConsumer> campaigns;
  private final ObjectProvider<AutomationTriggerConsumer> automation;
  private final ConsumerInbox inbox;

  public DomainEventRouter(
      ObjectMapper objectMapper,
      ObjectProvider<CustomerNotificationRequestedHandler> notifications,
      ObjectProvider<NotificationDispatchConsumer> dispatch,
      ObjectProvider<AutoKycOutboxConsumer> autoKyc,
      ObjectProvider<OrderDeliveredLoyaltyConsumer> loyalty,
      ObjectProvider<OrderDeliveredReferralConsumer> referral,
      ObjectProvider<OrderDeliveredCampaignConsumer> campaigns,
      ObjectProvider<AutomationTriggerConsumer> automation) {
    this(
        objectMapper,
        notifications,
        dispatch,
        autoKyc,
        loyalty,
        referral,
        campaigns,
        automation,
        null);
  }

  @Autowired
  public DomainEventRouter(
      ObjectMapper objectMapper,
      ObjectProvider<CustomerNotificationRequestedHandler> notifications,
      ObjectProvider<NotificationDispatchConsumer> dispatch,
      ObjectProvider<AutoKycOutboxConsumer> autoKyc,
      ObjectProvider<OrderDeliveredLoyaltyConsumer> loyalty,
      ObjectProvider<OrderDeliveredReferralConsumer> referral,
      ObjectProvider<OrderDeliveredCampaignConsumer> campaigns,
      ObjectProvider<AutomationTriggerConsumer> automation,
      ConsumerInbox inbox) {
    this.objectMapper = objectMapper;
    this.notifications = notifications;
    this.dispatch = dispatch;
    this.autoKyc = autoKyc;
    this.loyalty = loyalty;
    this.referral = referral;
    this.campaigns = campaigns;
    this.automation = automation;
    this.inbox = inbox;
  }

  public void handle(String messageBody) {
    if (messageBody == null || messageBody.isBlank()) {
      throw new IllegalStateException("Empty SQS payload");
    }
    Map<String, Object> root;
    try {
      root = objectMapper.readValue(messageBody, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse domain event", e);
    }
    String type = root.get("type") == null ? "" : String.valueOf(root.get("type"));
    if (type.isBlank()) {
      throw new IllegalStateException("Domain event missing type");
    }
    UUID id = eventId(root);
    if (inbox != null && inbox.alreadyProcessed("domain-event-router", id)) {
      log.info("Duplicate event skipped type={} id={}", type, id);
      return;
    }
    OutboxMessage envelope = new OutboxMessage(id, type, messageBody, Instant.now(), false);
    if (isKnownNotificationType(type)) {
      routeNotification(type, messageBody);
    } else {
      routeDomain(type, envelope, messageBody);
    }
    Optional.ofNullable(automation.getIfAvailable()).ifPresent(c -> c.accept(envelope));
    if (inbox != null) {
      inbox.claim("domain-event-router", id);
    }
  }

  private void routeDomain(String type, OutboxMessage envelope, String messageBody) {
    switch (type) {
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
      case "order.cancelled" -> {
        Optional.ofNullable(loyalty.getIfAvailable()).ifPresent(c -> c.accept(envelope));
        Optional.ofNullable(referral.getIfAvailable()).ifPresent(c -> c.accept(envelope));
      }
      default -> tryUnknown(type, messageBody);
    }
  }

  private void routeNotification(String type, String messageBody) {
    CustomerNotificationRequestedHandler handler = notifications.getIfAvailable();
    if (handler != null) {
      handler.handleMessage(messageBody);
      return;
    }
    NotificationDispatchConsumer consumer = dispatch.getIfAvailable();
    if (consumer != null) {
      consumer.handleMessage(messageBody);
      return;
    }
    throw new IllegalStateException("No consumer for " + type);
  }

  private void tryUnknown(String type, String messageBody) {
    NotificationDispatchConsumer consumer = dispatch.getIfAvailable();
    if (consumer != null && consumer.tryHandle(messageBody)) {
      return;
    }
    if (automation.getIfAvailable() != null) {
      return;
    }
    throw new IllegalStateException("Unsupported event type: " + type);
  }

  static boolean isKnownNotificationType(String type) {
    if (type == null || type.isBlank()) {
      return false;
    }
    return switch (type) {
      case "customer.notification.requested",
          "medicine_schedule.notification.dose_reminder",
          "medicine_schedule.notification.refill_alert",
          "marketing.campaign.dispatch.requested",
          "crm.invoice.dunning_step",
          "crm.invoice.payment_reminder",
          "crm.subscription.dunning_started",
          "crm.subscription.churn_survey",
          "crm.subscription.expired",
          "crm.subscription.winback",
          "crm.module.nudge",
          "observability.alert.critical_page",
          "observability.incident.declared",
          "observability.incident.postmortem_reminder",
          "inventory.po.sent",
          "pharmacy.kyc.expiry_alert" ->
          true;
      default ->
          type.startsWith("support.notification.")
              || type.startsWith("pharmacy.notification.")
              || type.startsWith("rider.notification.")
              || type.startsWith("marketing.notification.")
              || type.startsWith("crm.account.");
    };
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

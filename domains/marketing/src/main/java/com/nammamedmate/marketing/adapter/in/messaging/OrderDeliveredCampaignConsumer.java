package com.nammamedmate.marketing.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.marketing.application.CampaignService;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxMessage;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** order.delivered → campaign last-touch attribution (48h window). */
@Component
public class OrderDeliveredCampaignConsumer implements Consumer<OutboxMessage> {

  private final CampaignService campaigns;
  private final ObjectMapper objectMapper;

  public OrderDeliveredCampaignConsumer(CampaignService campaigns, ObjectMapper objectMapper) {
    this.campaigns = campaigns;
    this.objectMapper = objectMapper;
  }

  @Override
  public void accept(OutboxMessage message) {
    if (message == null || !"order.delivered".equals(message.type())) {
      return;
    }
    DomainEvent event;
    try {
      event = objectMapper.readValue(message.payloadJson(), DomainEvent.class);
    } catch (Exception e) {
      return;
    }
    Map<String, Object> payload = event.payload();
    UUID customerId = asUuid(payload.get("customer_id"));
    if (customerId == null) {
      return;
    }
    long total = asLong(payload.get("total_payable_paise"));
    if (total <= 0) {
      total = asLong(payload.get("item_total_paise"));
    }
    Instant deliveredAt = event.occurredAt() == null ? Instant.now() : event.occurredAt();
    campaigns.attributeOrder(customerId, total, deliveredAt);
  }

  private static UUID asUuid(Object raw) {
    if (raw == null) {
      return null;
    }
    try {
      return UUID.fromString(String.valueOf(raw));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static long asLong(Object raw) {
    if (raw instanceof Number n) {
      return n.longValue();
    }
    if (raw == null) {
      return 0L;
    }
    try {
      return Long.parseLong(String.valueOf(raw).trim());
    } catch (NumberFormatException e) {
      return 0L;
    }
  }
}

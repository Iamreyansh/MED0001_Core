package com.nammamedmate.marketing.adapter.in.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.marketing.application.CampaignService;
import com.nammamedmate.messaging.OutboxMessage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderDeliveredCampaignConsumerTest {

  @Test
  void attributesDeliveredOrderAndIgnoresNoise() {
    CampaignService campaigns = mock(CampaignService.class);
    OrderDeliveredCampaignConsumer consumer =
        new OrderDeliveredCampaignConsumer(campaigns, new ObjectMapper().findAndRegisterModules());
    consumer.accept(null);
    consumer.accept(new OutboxMessage(UUID.randomUUID(), "other", "{}", Instant.now(), false));
    verifyNoInteractions(campaigns);

    UUID customer = UUID.randomUUID();
    String json =
        "{\"type\":\"order.delivered\",\"occurredAt\":\"2026-08-22T10:00:00Z\",\"payload\":{\"customer_id\":\""
            + customer
            + "\",\"total_payable_paise\":52000}}";
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", json, Instant.now(), false));
    verify(campaigns).attributeOrder(eq(customer), eq(52000L), any());

    consumer.accept(
        new OutboxMessage(
            UUID.randomUUID(),
            "order.delivered",
            "{\"payload\":{\"customer_id\":\"" + customer + "\"}}",
            Instant.now(),
            false));
    verify(campaigns).attributeOrder(eq(customer), eq(0L), any());

    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", "{bad", Instant.now(), false));
    consumer.accept(
        new OutboxMessage(
            UUID.randomUUID(), "order.delivered", "{\"payload\":{}}", Instant.now(), false));
    consumer.accept(
        new OutboxMessage(
            UUID.randomUUID(),
            "order.delivered",
            "{\"payload\":{\"customer_id\":\"not-a-uuid\"}}",
            Instant.now(),
            false));

    String fallback =
        "{\"type\":\"order.delivered\",\"payload\":{\"customer_id\":\""
            + customer
            + "\",\"total_payable_paise\":\"x\",\"item_total_paise\":\"1250\"}}";
    consumer.accept(
        new OutboxMessage(UUID.randomUUID(), "order.delivered", fallback, Instant.now(), false));
    verify(campaigns).attributeOrder(eq(customer), eq(1250L), any());
  }
}

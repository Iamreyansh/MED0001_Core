package com.nammamedmate.marketing.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.marketing.application.port.out.NotificationDispatchPort;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StubMarketingNotificationDispatchTest {

  @Test
  void publishesOutboxEvents() {
    InMemoryOutboxStore store = new InMemoryOutboxStore();
    StubMarketingNotificationDispatch client =
        new StubMarketingNotificationDispatch(new OutboxPublisher(store, new ObjectMapper()));
    UUID id = UUID.randomUUID();
    client.notifyCouponBudgetExhausted("NAMMA25", id);
    client.notifyDailyBudgetBurnDigest(
        List.of(new NotificationDispatchPort.BudgetBurnItem("NAMMA25", 100, 80, 80.0)));
    client.notifyCampaignBudgetPaused("Monsoon", id);
    assertThat(store.all()).hasSize(3);
  }
}

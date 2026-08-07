package com.nammamedmate.pharmacy.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StubNotificationDispatchSettlementTest {

  private InMemoryOutboxStore outbox;
  private StubNotificationDispatchClient client;

  @BeforeEach
  void setUp() {
    outbox = new InMemoryOutboxStore();
    client = new StubNotificationDispatchClient(new OutboxPublisher(outbox, new ObjectMapper()));
  }

  @Test
  void settlementNotifications_enqueueOutbox() {
    UUID pharmacyId = Ids.newId();
    UUID settlementId = Ids.newId();
    client.dispatchSettlementReleased(pharmacyId, settlementId, 100L);
    client.dispatchSettlementPaid(pharmacyId, settlementId, 100L, "UTR");
    client.dispatchSettlementHeld(pharmacyId, settlementId, "hold");
    client.dispatchPerformanceAlert(pharmacyId, "LOW_FILL_RATE", "msg", List.of("EMAIL"));
    client.dispatchPharmacyNotice(
        pharmacyId,
        List.of("WHATSAPP", "IN_APP"),
        "PHARMACY_GENERAL_NOTICE",
        "Subject",
        "Body",
        "NORMAL");
    assertThat(outbox.all()).hasSize(5);
  }
}

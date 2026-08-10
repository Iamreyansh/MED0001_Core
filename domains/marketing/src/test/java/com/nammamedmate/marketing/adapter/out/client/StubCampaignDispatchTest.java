package com.nammamedmate.marketing.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.marketing.application.port.out.CampaignDispatchPort;
import com.nammamedmate.marketing.domain.Campaign;
import com.nammamedmate.marketing.domain.CampaignChannel;
import com.nammamedmate.marketing.domain.CampaignStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StubCampaignDispatchTest {

  @Test
  void dispatchesUntilBudgetAndLabels() {
    StubCampaignDispatch stub = new StubCampaignDispatch();
    assertThat(stub.rateCardLabel(CampaignChannel.PUSH)).contains("push");
    assertThat(stub.rateCardLabel(CampaignChannel.SMS)).contains("SMS");
    assertThat(stub.rateCardLabel(CampaignChannel.EMAIL)).contains("email");
    assertThat(stub.rateCardLabel(CampaignChannel.WHATSAPP)).contains("0.85");

    UUID c1 = UUID.randomUUID();
    UUID c2 = UUID.randomUUID();
    Campaign campaign =
        new Campaign(
            UUID.randomUUID(),
            "x",
            CampaignChannel.SMS,
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            30L,
            0L,
            0,
            0,
            0,
            0,
            0,
            0L,
            null,
            CampaignStatus.SCHEDULED,
            null,
            Instant.now(),
            Instant.now());
    CampaignDispatchPort.DispatchResult r = stub.dispatch(campaign, List.of(c1, c2), 20L);
    assertThat(r.sentDelta()).isEqualTo(2);
    assertThat(r.spendDeltaPaise()).isEqualTo(40L);
    assertThat(r.budgetPaused()).isTrue();
    assertThat(r.deliveredCustomerIds()).containsExactly(c1, c2);

    Campaign noCap =
        new Campaign(
            UUID.randomUUID(),
            "y",
            CampaignChannel.PUSH,
            UUID.randomUUID(),
            null,
            "s",
            "b",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0L,
            0,
            0,
            0,
            0,
            0,
            0L,
            null,
            CampaignStatus.DRAFT,
            null,
            Instant.now(),
            Instant.now());
    assertThat(stub.dispatch(noCap, List.of(), 1L).sentDelta()).isZero();
    assertThat(stub.dispatch(noCap, null, 1L).sentDelta()).isZero();
    assertThat(stub.dispatch(noCap, List.of(c1), 1L).budgetPaused()).isFalse();
    Campaign mid =
        new Campaign(
            UUID.randomUUID(),
            "m",
            CampaignChannel.SMS,
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            100L,
            0L,
            0,
            0,
            0,
            0,
            0,
            0L,
            null,
            CampaignStatus.RUNNING,
            null,
            Instant.now(),
            Instant.now());
    // under cap then over after one message (cost equals remaining)
    assertThat(stub.dispatch(mid, List.of(c1, c2), 100L).sentDelta()).isEqualTo(1);
    assertThat(stub.dispatch(mid, List.of(c1), 50L).budgetPaused()).isFalse();
  }
}

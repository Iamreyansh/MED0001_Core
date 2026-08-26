package com.nammamedmate.marketing.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.marketing.application.port.out.CampaignDispatchPort;
import com.nammamedmate.marketing.domain.Campaign;
import com.nammamedmate.marketing.domain.CampaignChannel;
import com.nammamedmate.marketing.domain.CampaignStatus;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class OutboxCampaignDispatchTest {

  @Test
  void publishesUntilBudgetAndLabels() {
    OutboxPublisher publisher = mock(OutboxPublisher.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<OutboxPublisher> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(publisher);
    OutboxCampaignDispatch port = new OutboxCampaignDispatch(provider);

    assertThat(port.rateCardLabel(CampaignChannel.PUSH)).contains("push");
    assertThat(port.rateCardLabel(CampaignChannel.SMS)).contains("SMS");
    assertThat(port.rateCardLabel(CampaignChannel.EMAIL)).contains("email");
    assertThat(port.rateCardLabel(CampaignChannel.WHATSAPP)).contains("0.85");

    UUID c1 = UUID.randomUUID();
    UUID c2 = UUID.randomUUID();
    Campaign campaign = campaign(30L, 0L, CampaignChannel.SMS);
    CampaignDispatchPort.DispatchResult r = port.dispatch(campaign, List.of(c1, c2), 20L);
    assertThat(r.sentDelta()).isEqualTo(2);
    assertThat(r.deliveredDelta()).isEqualTo(2);
    assertThat(r.spendDeltaPaise()).isEqualTo(40L);
    assertThat(r.budgetPaused()).isTrue();
    assertThat(r.deliveredCustomerIds()).containsExactly(c1, c2);
    verify(publisher, times(2)).publish(any(DomainEvent.class));

    assertThat(port.dispatch(campaign, List.of(), 10L).sentDelta()).isZero();
    assertThat(port.dispatch(campaign, null, 10L).sentDelta()).isZero();
    assertThat(
            port.dispatch(campaign(10L, 10L, CampaignChannel.PUSH), List.of(c1), 1L).budgetPaused())
        .isTrue();

    OutboxCampaignDispatch noOutbox = new OutboxCampaignDispatch(null);
    assertThat(
            noOutbox
                .dispatch(campaign(null, 0L, CampaignChannel.EMAIL), List.of(c1), 5L)
                .sentDelta())
        .isEqualTo(1);

    @SuppressWarnings("unchecked")
    ObjectProvider<OutboxPublisher> empty = mock(ObjectProvider.class);
    when(empty.getIfAvailable()).thenReturn(null);
    assertThat(
            new OutboxCampaignDispatch(empty)
                .dispatch(campaign(null, 0L, CampaignChannel.PUSH), List.of(c1), 1L)
                .sentDelta())
        .isEqualTo(1);
  }

  private static Campaign campaign(Long cap, long spend, CampaignChannel channel) {
    return new Campaign(
        UUID.randomUUID(),
        "x",
        channel,
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
        cap,
        spend,
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
  }
}

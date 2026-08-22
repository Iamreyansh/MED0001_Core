package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.customer.adapter.in.messaging.OrderDeliveredLoyaltyConsumer;
import com.nammamedmate.customer.adapter.in.messaging.OrderDeliveredReferralConsumer;
import com.nammamedmate.messaging.OutboxMessage;
import com.nammamedmate.messaging.OutboxStore;
import com.nammamedmate.pharmacy.adapter.in.messaging.AutoKycOutboxConsumer;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;

class PlatformConfigGuardsTest {

  private final PlatformConfig cfg = new PlatformConfig();

  @Test
  void deployedGuardsRejectPlaceholders() throws Exception {
    assertThatThrownBy(() -> cfg.internalServiceTokenGuard("").run(null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> cfg.internalServiceTokenGuard("local-internal-wallet-token").run(null))
        .isInstanceOf(IllegalStateException.class);
    cfg.internalServiceTokenGuard("injected-token").run(null);

    assertThatThrownBy(() -> cfg.sqsQueueUrlGuard(" ").run(null))
        .isInstanceOf(IllegalStateException.class);
    cfg.sqsQueueUrlGuard("https://sqs.example/queue").run(null);
  }

  @Test
  @SuppressWarnings("unchecked")
  void localDispatcherFallsBackToInProcessConsumers() {
    OutboxStore store = mock(OutboxStore.class);
    ObjectProvider<SqsClient> sqs = mock(ObjectProvider.class);
    AutoKycOutboxConsumer kyc = mock(AutoKycOutboxConsumer.class);
    OrderDeliveredReferralConsumer referral = mock(OrderDeliveredReferralConsumer.class);
    OrderDeliveredLoyaltyConsumer loyalty = mock(OrderDeliveredLoyaltyConsumer.class);
    OutboxMessage msg =
        new OutboxMessage(
            java.util.UUID.randomUUID(), "order.delivered", "{}", java.time.Instant.now(), false);
    when(store.claimUnpublished(anyInt(), any(), any())).thenReturn(java.util.List.of(msg));
    var dispatcher = cfg.sqsEventDispatcher(store, sqs, "", kyc, referral, loyalty);
    dispatcher.dispatchOnce();
    verify(kyc).accept(msg);
    verify(referral).accept(msg);
    verify(loyalty).accept(msg);

    SqsClient client = mock(SqsClient.class);
    when(sqs.getObject()).thenReturn(client);
    cfg.sqsEventDispatcher(store, sqs, "https://sqs.example/q", kyc, referral, loyalty)
        .dispatchOnce();
    verify(client)
        .sendMessage(any(software.amazon.awssdk.services.sqs.model.SendMessageRequest.class));
  }

  @Test
  void localBeans() throws Exception {
    assertThatCode(() -> cfg.clock()).doesNotThrowAnyException();
    cfg.localAesGcmCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    cfg.outboxStore(mock(JdbcTemplate.class));
    cfg.outboxPublisher(mock(OutboxStore.class), new ObjectMapper());
    cfg.schedulerLease(mock(JdbcTemplate.class), Clock.systemUTC());
    cfg.localPresignedUrlService("b").createGetUrl("k", java.time.Duration.ofMinutes(1));
  }
}

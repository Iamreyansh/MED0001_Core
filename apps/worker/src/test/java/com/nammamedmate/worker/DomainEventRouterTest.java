package com.nammamedmate.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.customer.adapter.in.messaging.OrderDeliveredLoyaltyConsumer;
import com.nammamedmate.customer.adapter.in.messaging.OrderDeliveredReferralConsumer;
import com.nammamedmate.marketing.adapter.in.messaging.OrderDeliveredCampaignConsumer;
import com.nammamedmate.messaging.OutboxMessage;
import com.nammamedmate.notification.adapter.in.messaging.CustomerNotificationRequestedHandler;
import com.nammamedmate.pharmacy.adapter.in.messaging.AutoKycOutboxConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DomainEventRouterTest {

  @Test
  void routesKnownTypesAndIgnoresBlank() {
    CustomerNotificationRequestedHandler notes = mock(CustomerNotificationRequestedHandler.class);
    AutoKycOutboxConsumer kyc = mock(AutoKycOutboxConsumer.class);
    OrderDeliveredLoyaltyConsumer loyalty = mock(OrderDeliveredLoyaltyConsumer.class);
    OrderDeliveredReferralConsumer referral = mock(OrderDeliveredReferralConsumer.class);
    OrderDeliveredCampaignConsumer campaigns = mock(OrderDeliveredCampaignConsumer.class);
    DomainEventRouter router = router(notes, kyc, loyalty, referral, campaigns);

    router.handle(null);
    router.handle("  ");
    verifyNoInteractions(notes, kyc);

    router.handle("{\"type\":\"customer.notification.requested\"}");
    verify(notes).handleMessage("{\"type\":\"customer.notification.requested\"}");

    router.handle("{\"type\":\"pharmacy.kyc.auto_verify_requested\"}");
    verify(kyc).accept(any(OutboxMessage.class));

    router.handle("{\"type\":\"order.delivered\",\"eventId\":\"not-a-uuid\"}");
    verify(loyalty).accept(any(OutboxMessage.class));
    verify(referral).accept(any(OutboxMessage.class));
    verify(campaigns).accept(any(OutboxMessage.class));

    router.handle("{\"type\":\"unknown.event\"}");
    router.handle("{\"type\":\"pharmacy.kyc.async_check_requested\"}");
    router.handle("{\"event_id\":\"11111111-1111-4111-8111-111111111111\"}");
    router.handle(
        "{\"type\":\"customer.notification.requested\",\"eventId\":\"11111111-1111-4111-8111-111111111111\"}");
  }

  @Test
  void orderDeliveredPartialConsumersAndEventIdFallback() {
    OrderDeliveredLoyaltyConsumer loyalty = mock(OrderDeliveredLoyaltyConsumer.class);
    OrderDeliveredReferralConsumer referral = mock(OrderDeliveredReferralConsumer.class);
    OrderDeliveredCampaignConsumer campaigns = mock(OrderDeliveredCampaignConsumer.class);

    router(null, null, loyalty, null, null).handle("{\"type\":\"order.delivered\"}");
    verify(loyalty).accept(any(OutboxMessage.class));

    router(null, null, null, referral, null).handle("{\"type\":\"order.delivered\"}");
    verify(referral).accept(any(OutboxMessage.class));

    router(null, null, null, null, campaigns)
        .handle(
            "{\"type\":\"order.delivered\",\"event_id\":\"22222222-2222-4222-8222-222222222222\"}");
    verify(campaigns).accept(any(OutboxMessage.class));
  }

  @Test
  void failsClosedWhenConsumerMissingOrJsonInvalid() {
    DomainEventRouter empty = router(null, null, null, null, null);
    assertThatThrownBy(() -> empty.handle("{\"type\":\"customer.notification.requested\"}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> empty.handle("{\"type\":\"pharmacy.kyc.async_check_requested\"}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> empty.handle("{\"type\":\"order.delivered\"}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> empty.handle("{bad")).isInstanceOf(IllegalStateException.class);
  }

  @SuppressWarnings("unchecked")
  private static DomainEventRouter router(
      CustomerNotificationRequestedHandler notes,
      AutoKycOutboxConsumer kyc,
      OrderDeliveredLoyaltyConsumer loyalty,
      OrderDeliveredReferralConsumer referral,
      OrderDeliveredCampaignConsumer campaigns) {
    ObjectProvider<CustomerNotificationRequestedHandler> n = mock(ObjectProvider.class);
    ObjectProvider<AutoKycOutboxConsumer> k = mock(ObjectProvider.class);
    ObjectProvider<OrderDeliveredLoyaltyConsumer> l = mock(ObjectProvider.class);
    ObjectProvider<OrderDeliveredReferralConsumer> r = mock(ObjectProvider.class);
    ObjectProvider<OrderDeliveredCampaignConsumer> c = mock(ObjectProvider.class);
    when(n.getIfAvailable()).thenReturn(notes);
    when(k.getIfAvailable()).thenReturn(kyc);
    when(l.getIfAvailable()).thenReturn(loyalty);
    when(r.getIfAvailable()).thenReturn(referral);
    when(c.getIfAvailable()).thenReturn(campaigns);
    return new DomainEventRouter(new ObjectMapper(), n, k, l, r, c);
  }
}

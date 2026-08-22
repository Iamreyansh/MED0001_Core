package com.nammamedmate.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.adapter.in.messaging.AutomationTriggerConsumer;
import com.nammamedmate.customer.adapter.in.messaging.OrderDeliveredLoyaltyConsumer;
import com.nammamedmate.customer.adapter.in.messaging.OrderDeliveredReferralConsumer;
import com.nammamedmate.marketing.adapter.in.messaging.OrderDeliveredCampaignConsumer;
import com.nammamedmate.messaging.OutboxMessage;
import com.nammamedmate.notification.adapter.in.messaging.CustomerNotificationRequestedHandler;
import com.nammamedmate.notification.adapter.in.messaging.NotificationDispatchConsumer;
import com.nammamedmate.pharmacy.adapter.in.messaging.AutoKycOutboxConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DomainEventRouterTest {

  @Test
  void routesKnownTypesAndIgnoresBlank() {
    CustomerNotificationRequestedHandler notes = mock(CustomerNotificationRequestedHandler.class);
    NotificationDispatchConsumer dispatch = mock(NotificationDispatchConsumer.class);
    AutoKycOutboxConsumer kyc = mock(AutoKycOutboxConsumer.class);
    OrderDeliveredLoyaltyConsumer loyalty = mock(OrderDeliveredLoyaltyConsumer.class);
    OrderDeliveredReferralConsumer referral = mock(OrderDeliveredReferralConsumer.class);
    OrderDeliveredCampaignConsumer campaigns = mock(OrderDeliveredCampaignConsumer.class);
    DomainEventRouter router = router(notes, dispatch, kyc, loyalty, referral, campaigns, null);

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
    router.handle("{\"type\":\"order.placed\"}");
    router.handle("{\"type\":\"pharmacy.kyc.async_check_requested\"}");
    router.handle("{\"event_id\":\"11111111-1111-4111-8111-111111111111\"}");
    router.handle(
        "{\"type\":\"customer.notification.requested\",\"eventId\":\"11111111-1111-4111-8111-111111111111\"}");
  }

  @Test
  void routesNotificationTypesToHandler() {
    CustomerNotificationRequestedHandler notes = mock(CustomerNotificationRequestedHandler.class);
    DomainEventRouter router = router(notes, null, null, null, null, null, null);
    router.handle("{\"type\":\"medicine_schedule.notification.dose_reminder\"}");
    router.handle("{\"type\":\"medicine_schedule.notification.refill_alert\"}");
    router.handle("{\"type\":\"marketing.campaign.dispatch.requested\"}");
    router.handle("{\"type\":\"crm.invoice.dunning_step\"}");
    router.handle("{\"type\":\"crm.subscription.dunning_started\"}");
    router.handle("{\"type\":\"crm.module.nudge\"}");
    router.handle("{\"type\":\"support.notification.escalated\"}");
    router.handle("{\"type\":\"observability.alert.critical_page\"}");
    router.handle("{\"type\":\"observability.incident.declared\"}");
    router.handle("{\"type\":\"inventory.po.sent\"}");
    router.handle("{\"type\":\"pharmacy.notification.notice\"}");
    router.handle("{\"type\":\"marketing.notification.campaign_budget_paused\"}");
    router.handle("{\"type\":\"crm.account.save_play_needed\"}");
    router.handle("{\"type\":\"crm.invoice.payment_reminder\"}");
    router.handle("{\"type\":\"crm.subscription.churn_survey\"}");
    router.handle("{\"type\":\"crm.subscription.expired\"}");
    router.handle("{\"type\":\"crm.subscription.winback\"}");
    router.handle("{\"type\":\"observability.incident.postmortem_reminder\"}");
    verify(notes, org.mockito.Mockito.times(18)).handleMessage(anyString());
  }

  @Test
  void knownNotificationFallsBackToDispatchConsumer() {
    NotificationDispatchConsumer dispatch = mock(NotificationDispatchConsumer.class);
    router(null, dispatch, null, null, null, null, null)
        .handle("{\"type\":\"crm.invoice.payment_reminder\"}");
    verify(dispatch).handleMessage("{\"type\":\"crm.invoice.payment_reminder\"}");
  }

  @Test
  void unknownDispatchableUsesCatchAll() {
    NotificationDispatchConsumer dispatch = mock(NotificationDispatchConsumer.class);
    when(dispatch.tryHandle(anyString())).thenReturn(true);
    router(null, dispatch, null, null, null, null, null)
        .handle("{\"type\":\"misc.ping\",\"channel\":\"PUSH\",\"title\":\"t\",\"body\":\"b\"}");
    verify(dispatch).tryHandle(anyString());
  }

  @Test
  void unknownNonDispatchableAcks() {
    NotificationDispatchConsumer dispatch = mock(NotificationDispatchConsumer.class);
    when(dispatch.tryHandle(anyString())).thenReturn(false);
    router(null, dispatch, null, null, null, null, null)
        .handle("{\"type\":\"automation.action.executed\"}");
    verify(dispatch).tryHandle(anyString());
    verify(dispatch, never()).handleMessage(anyString());
  }

  @Test
  void routesAutomationActionWhenConsumerPresent() {
    AutomationTriggerConsumer automation = mock(AutomationTriggerConsumer.class);
    DomainEventRouter routed = router(null, null, null, null, null, null, automation);
    routed.handle("{\"type\":\"automation.action.executed\"}");
    routed.handle("{\"type\":\"order.placed\"}");
    routed.handle("{\"type\":\"order.cancelled\"}");
    verify(automation, org.mockito.Mockito.times(3)).accept(any(OutboxMessage.class));
  }

  @Test
  void orderDeliveredPartialConsumersAndEventIdFallback() {
    OrderDeliveredLoyaltyConsumer loyalty = mock(OrderDeliveredLoyaltyConsumer.class);
    OrderDeliveredReferralConsumer referral = mock(OrderDeliveredReferralConsumer.class);
    OrderDeliveredCampaignConsumer campaigns = mock(OrderDeliveredCampaignConsumer.class);

    router(null, null, null, loyalty, null, null, null).handle("{\"type\":\"order.delivered\"}");
    verify(loyalty).accept(any(OutboxMessage.class));

    router(null, null, null, null, referral, null, null).handle("{\"type\":\"order.delivered\"}");
    verify(referral).accept(any(OutboxMessage.class));

    router(null, null, null, null, null, campaigns, null)
        .handle(
            "{\"type\":\"order.delivered\",\"event_id\":\"22222222-2222-4222-8222-222222222222\"}");
    verify(campaigns).accept(any(OutboxMessage.class));

    OrderDeliveredLoyaltyConsumer cancelLoyalty = mock(OrderDeliveredLoyaltyConsumer.class);
    OrderDeliveredReferralConsumer cancelReferral = mock(OrderDeliveredReferralConsumer.class);
    router(null, null, null, cancelLoyalty, cancelReferral, null, null)
        .handle("{\"type\":\"order.cancelled\"}");
    verify(cancelLoyalty).accept(any(OutboxMessage.class));
    verify(cancelReferral).accept(any(OutboxMessage.class));
  }

  @Test
  void failsClosedWhenConsumerMissingOrJsonInvalid() {
    DomainEventRouter empty = router(null, null, null, null, null, null, null);
    assertThatThrownBy(() -> empty.handle("{\"type\":\"customer.notification.requested\"}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> empty.handle("{\"type\":\"pharmacy.kyc.async_check_requested\"}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> empty.handle("{\"type\":\"order.delivered\"}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> empty.handle("{bad")).isInstanceOf(IllegalStateException.class);
    empty.handle("{\"type\":\"unknown.event\"}");
  }

  @Test
  void knownTypePredicate() {
    assertThat(DomainEventRouter.isKnownNotificationType(null)).isFalse();
    assertThat(DomainEventRouter.isKnownNotificationType(" ")).isFalse();
    assertThat(DomainEventRouter.isKnownNotificationType("crm.invoice.dunning_step")).isTrue();
    assertThat(DomainEventRouter.isKnownNotificationType("support.notification.csat_survey"))
        .isTrue();
    assertThat(DomainEventRouter.isKnownNotificationType("pharmacy.kyc.auto_verify_requested"))
        .isFalse();
  }

  @SuppressWarnings("unchecked")
  private static DomainEventRouter router(
      CustomerNotificationRequestedHandler notes,
      NotificationDispatchConsumer dispatch,
      AutoKycOutboxConsumer kyc,
      OrderDeliveredLoyaltyConsumer loyalty,
      OrderDeliveredReferralConsumer referral,
      OrderDeliveredCampaignConsumer campaigns,
      AutomationTriggerConsumer automation) {
    ObjectProvider<CustomerNotificationRequestedHandler> n = mock(ObjectProvider.class);
    ObjectProvider<NotificationDispatchConsumer> d = mock(ObjectProvider.class);
    ObjectProvider<AutoKycOutboxConsumer> k = mock(ObjectProvider.class);
    ObjectProvider<OrderDeliveredLoyaltyConsumer> l = mock(ObjectProvider.class);
    ObjectProvider<OrderDeliveredReferralConsumer> r = mock(ObjectProvider.class);
    ObjectProvider<OrderDeliveredCampaignConsumer> c = mock(ObjectProvider.class);
    ObjectProvider<AutomationTriggerConsumer> a = mock(ObjectProvider.class);
    when(n.getIfAvailable()).thenReturn(notes);
    when(d.getIfAvailable()).thenReturn(dispatch);
    when(k.getIfAvailable()).thenReturn(kyc);
    when(l.getIfAvailable()).thenReturn(loyalty);
    when(r.getIfAvailable()).thenReturn(referral);
    when(c.getIfAvailable()).thenReturn(campaigns);
    when(a.getIfAvailable()).thenReturn(automation);
    return new DomainEventRouter(new ObjectMapper(), n, d, k, l, r, c, a);
  }
}

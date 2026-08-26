package com.nammamedmate.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.nammamedmate.messaging.ConsumerInbox;
import com.nammamedmate.messaging.OutboxMessage;
import com.nammamedmate.notification.adapter.in.messaging.CustomerNotificationRequestedHandler;
import com.nammamedmate.notification.adapter.in.messaging.NotificationDispatchConsumer;
import com.nammamedmate.rider.adapter.in.messaging.AutomationRiderAssignConsumer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DomainEventRouterTest {

  @Test
  void routesKnownTypesAndRejectsBlank() {
    CustomerNotificationRequestedHandler notes = mock(CustomerNotificationRequestedHandler.class);
    NotificationDispatchConsumer dispatch = mock(NotificationDispatchConsumer.class);
    OrderDeliveredLoyaltyConsumer loyalty = mock(OrderDeliveredLoyaltyConsumer.class);
    OrderDeliveredReferralConsumer referral = mock(OrderDeliveredReferralConsumer.class);
    OrderDeliveredCampaignConsumer campaigns = mock(OrderDeliveredCampaignConsumer.class);
    DomainEventRouter router = router(notes, dispatch, loyalty, referral, campaigns, null, null);

    assertThatThrownBy(() -> router.handle(null)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> router.handle("  ")).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(notes);

    router.handle("{\"type\":\"customer.notification.requested\"}");
    verify(notes).handleMessage("{\"type\":\"customer.notification.requested\"}");

    // Auto-KYC removed — leftover outbox events are ignored.
    router.handle("{\"type\":\"pharmacy.kyc.auto_verify_requested\"}");
    router.handle("{\"type\":\"pharmacy.kyc.async_check_requested\"}");
    verifyNoInteractions(loyalty, referral, campaigns);

    router.handle("{\"type\":\"order.delivered\",\"eventId\":\"not-a-uuid\"}");
    verify(loyalty).accept(any(OutboxMessage.class));
    verify(referral).accept(any(OutboxMessage.class));
    verify(campaigns).accept(any(OutboxMessage.class));

    assertThatThrownBy(() -> router.handle("{\"type\":\"unknown.event\"}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> router.handle("{\"type\":\"order.placed\"}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> router.handle("{\"event_id\":\"11111111-1111-4111-8111-111111111111\"}"))
        .isInstanceOf(IllegalStateException.class);
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
  void unknownNonDispatchableFailsClosed() {
    NotificationDispatchConsumer dispatch = mock(NotificationDispatchConsumer.class);
    when(dispatch.tryHandle(anyString())).thenReturn(false);
    assertThatThrownBy(
            () ->
                router(null, dispatch, null, null, null, null, null)
                    .handle("{\"type\":\"automation.action.executed\"}"))
        .isInstanceOf(IllegalStateException.class);
    verify(dispatch).tryHandle(anyString());
    verify(dispatch, never()).handleMessage(anyString());
  }

  @Test
  void routesAutomationActionWhenConsumerPresent() {
    AutomationTriggerConsumer automation = mock(AutomationTriggerConsumer.class);
    DomainEventRouter routed = router(null, null, null, null, null, automation, null);
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

    router(null, null, loyalty, null, null, null, null).handle("{\"type\":\"order.delivered\"}");
    verify(loyalty).accept(any(OutboxMessage.class));

    router(null, null, null, referral, null, null, null).handle("{\"type\":\"order.delivered\"}");
    verify(referral).accept(any(OutboxMessage.class));

    router(null, null, null, null, campaigns, null, null)
        .handle(
            "{\"type\":\"order.delivered\",\"event_id\":\"22222222-2222-4222-8222-222222222222\"}");
    verify(campaigns).accept(any(OutboxMessage.class));

    OrderDeliveredLoyaltyConsumer cancelLoyalty = mock(OrderDeliveredLoyaltyConsumer.class);
    OrderDeliveredReferralConsumer cancelReferral = mock(OrderDeliveredReferralConsumer.class);
    router(null, null, cancelLoyalty, cancelReferral, null, null, null)
        .handle("{\"type\":\"order.cancelled\"}");
    verify(cancelLoyalty).accept(any(OutboxMessage.class));
    verify(cancelReferral).accept(any(OutboxMessage.class));
  }

  @Test
  void failsClosedWhenConsumerMissingOrJsonInvalid() {
    DomainEventRouter empty = router(null, null, null, null, null, null, null);
    assertThatThrownBy(() -> empty.handle("{\"type\":\"customer.notification.requested\"}"))
        .isInstanceOf(IllegalStateException.class);
    // Removed auto-KYC events must not fail the worker.
    empty.handle("{\"type\":\"pharmacy.kyc.async_check_requested\"}");
    assertThatThrownBy(() -> empty.handle("{\"type\":\"order.delivered\"}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> empty.handle("{bad")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> empty.handle("{\"type\":\"unknown.event\"}"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void knownTypePredicate() {
    assertThat(DomainEventRouter.isKnownNotificationType(null)).isFalse();
    assertThat(DomainEventRouter.isKnownNotificationType(" ")).isFalse();
    assertThat(DomainEventRouter.isKnownNotificationType("crm.invoice.dunning_step")).isTrue();
    assertThat(DomainEventRouter.isKnownNotificationType("support.notification.csat_survey"))
        .isTrue();
    assertThat(DomainEventRouter.isKnownNotificationType("rider.notification.assigned")).isTrue();
    assertThat(DomainEventRouter.isKnownNotificationType("pharmacy.kyc.auto_verify_requested"))
        .isFalse();
    assertThat(DomainEventRouter.isKnownNotificationType("pharmacy.kyc.expiry_alert")).isTrue();
  }

  @Test
  void skipsDuplicateWhenInboxAlreadyProcessed() {
    ConsumerInbox inbox = mock(ConsumerInbox.class);
    when(inbox.alreadyProcessed(anyString(), any())).thenReturn(true);
    CustomerNotificationRequestedHandler notes = mock(CustomerNotificationRequestedHandler.class);
    DomainEventRouter routed = router(notes, null, null, null, null, null, null, inbox);
    routed.handle(
        "{\"type\":\"customer.notification.requested\",\"eventId\":\"11111111-1111-4111-8111-111111111111\"}");
    verify(notes, never()).handleMessage(anyString());
    verify(inbox, never()).claim(anyString(), any());
  }

  @Test
  void claimsInboxAfterSuccessfulRoute() {
    ConsumerInbox inbox = mock(ConsumerInbox.class);
    when(inbox.alreadyProcessed(anyString(), any())).thenReturn(false);
    CustomerNotificationRequestedHandler notes = mock(CustomerNotificationRequestedHandler.class);
    DomainEventRouter routed = router(notes, null, null, null, null, null, null, inbox);
    UUID eventId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    routed.handle(
        "{\"type\":\"customer.notification.requested\",\"eventId\":\"11111111-1111-4111-8111-111111111111\"}");
    verify(notes).handleMessage(anyString());
    verify(inbox).claim(eq("domain-event-router"), eq(eventId));
  }

  @Test
  void routesRiderAssignRequested() {
    AutomationRiderAssignConsumer riderAssign = mock(AutomationRiderAssignConsumer.class);
    router(null, null, null, null, null, null, riderAssign)
        .handle("{\"type\":\"automation.rider.assign_requested\"}");
    verify(riderAssign).accept(any(OutboxMessage.class));
  }

  @Test
  void failsClosedWhenRiderAssignConsumerMissing() {
    DomainEventRouter empty = router(null, null, null, null, null, null, null);
    assertThatThrownBy(() -> empty.handle("{\"type\":\"automation.rider.assign_requested\"}"))
        .isInstanceOf(IllegalStateException.class);
  }

  private static DomainEventRouter router(
      CustomerNotificationRequestedHandler notes,
      NotificationDispatchConsumer dispatch,
      OrderDeliveredLoyaltyConsumer loyalty,
      OrderDeliveredReferralConsumer referral,
      OrderDeliveredCampaignConsumer campaigns,
      AutomationTriggerConsumer automation,
      AutomationRiderAssignConsumer riderAssign) {
    return router(notes, dispatch, loyalty, referral, campaigns, automation, riderAssign, null);
  }

  @SuppressWarnings("unchecked")
  private static DomainEventRouter router(
      CustomerNotificationRequestedHandler notes,
      NotificationDispatchConsumer dispatch,
      OrderDeliveredLoyaltyConsumer loyalty,
      OrderDeliveredReferralConsumer referral,
      OrderDeliveredCampaignConsumer campaigns,
      AutomationTriggerConsumer automation,
      AutomationRiderAssignConsumer riderAssign,
      ConsumerInbox inbox) {
    ObjectProvider<CustomerNotificationRequestedHandler> n = mock(ObjectProvider.class);
    ObjectProvider<NotificationDispatchConsumer> d = mock(ObjectProvider.class);
    ObjectProvider<OrderDeliveredLoyaltyConsumer> l = mock(ObjectProvider.class);
    ObjectProvider<OrderDeliveredReferralConsumer> r = mock(ObjectProvider.class);
    ObjectProvider<OrderDeliveredCampaignConsumer> c = mock(ObjectProvider.class);
    ObjectProvider<AutomationTriggerConsumer> a = mock(ObjectProvider.class);
    ObjectProvider<AutomationRiderAssignConsumer> ra = mock(ObjectProvider.class);
    when(n.getIfAvailable()).thenReturn(notes);
    when(d.getIfAvailable()).thenReturn(dispatch);
    when(l.getIfAvailable()).thenReturn(loyalty);
    when(r.getIfAvailable()).thenReturn(referral);
    when(c.getIfAvailable()).thenReturn(campaigns);
    when(a.getIfAvailable()).thenReturn(automation);
    when(ra.getIfAvailable()).thenReturn(riderAssign);
    return new DomainEventRouter(new ObjectMapper(), n, d, l, r, c, a, ra, inbox);
  }
}

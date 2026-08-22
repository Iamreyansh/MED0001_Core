package com.nammamedmate.notification.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.application.EmailSendService;
import com.nammamedmate.notification.application.InAppNotificationService;
import com.nammamedmate.notification.application.PushSendService;
import com.nammamedmate.notification.application.SmsSendService;
import com.nammamedmate.notification.application.WhatsAppSendService;
import com.nammamedmate.notification.application.port.out.RecipientIdentityPort;
import com.nammamedmate.notification.domain.InAppNotification;
import com.nammamedmate.notification.domain.InAppNotificationType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomerNotificationRequestedHandlerTest {

  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");

  private InAppNotificationService inApp;
  private PushSendService push;
  private SmsSendService sms;
  private WhatsAppSendService whatsapp;
  private EmailSendService email;
  private RecipientIdentityPort identities;
  private CustomerNotificationRequestedHandler handler;

  @BeforeEach
  void setUp() {
    inApp = mock(InAppNotificationService.class);
    push = mock(PushSendService.class);
    sms = mock(SmsSendService.class);
    whatsapp = mock(WhatsAppSendService.class);
    email = mock(EmailSendService.class);
    identities = mock(RecipientIdentityPort.class);
    when(inApp.create(any(), any(), any(), any(), any()))
        .thenReturn(
            new InAppNotification(
                Ids.newId(),
                CUST,
                InAppNotificationType.ORDER_UPDATE,
                "t",
                "b",
                null,
                false,
                false,
                null,
                null,
                null));
    handler =
        new CustomerNotificationRequestedHandler(
            inApp, new ObjectMapper(), push, sms, whatsapp, email, identities);
  }

  @Test
  void pushCreatesInAppAndCallsFcm() {
    handler.handlePayload(
        Map.of(
            "customer_id",
            CUST.toString(),
            "channel",
            "PUSH",
            "title",
            "Out for delivery",
            "body",
            "Rider is coming",
            "order_id",
            "o0000001-0000-4000-8000-000000000001"));
    verify(inApp)
        .create(
            CUST,
            InAppNotificationType.ORDER_UPDATE,
            "Out for delivery",
            "Rider is coming",
            "nmmedmate://order/o0000001-0000-4000-8000-000000000001");
    verify(push).send(any(PushSendService.SendCommand.class));
  }

  @Test
  void smsWhatsappEmailUseTemplateWithoutTitle() {
    when(identities.findPhoneByCustomerId(CUST)).thenReturn(Optional.of("+919876543210"));
    handler.handlePayload(
        Map.of(
            "customer_id",
            CUST.toString(),
            "channels",
            List.of("SMS", "WHATSAPP", "EMAIL"),
            "template",
            "delivery_otp",
            "email",
            "a@b.com",
            "variables",
            Map.of("1", "1234"),
            "components",
            List.of(Map.of("type", "body"))));
    verify(sms).send(any(SmsSendService.SendCommand.class));
    verify(whatsapp).send(any(WhatsAppSendService.SendCommand.class));
    verify(email).send(any(EmailSendService.SendCommand.class));
    verifyNoInteractions(push);
  }

  @Test
  void doseReminderGeneratesTitleBody() {
    handler.handlePayload(
        "medicine_schedule.notification.dose_reminder",
        Map.of("customer_id", CUST.toString(), "medicine_name", "Metformin"));
    verify(inApp)
        .create(
            CUST,
            InAppNotificationType.ORDER_UPDATE,
            "Dose reminder",
            "Time to take Metformin",
            null);
    verify(push).send(any(PushSendService.SendCommand.class));
  }

  @Test
  void refillAlertGeneratesTitleBody() {
    handler.handlePayload(
        "medicine_schedule.notification.refill_alert",
        Map.of("customer_id", CUST.toString(), "template", "REFILL_ALERT"));
    verify(inApp)
        .create(
            CUST,
            InAppNotificationType.REFILL_REMINDER,
            "Refill alert",
            "Time to refill your medicine",
            null);
  }

  @Test
  void sendFailuresPropagate() {
    when(sms.send(any())).thenThrow(new AppException("ALL_PROVIDERS_FAILED", "down", 503));
    assertThatThrownBy(
            () ->
                handler.handlePayload(
                    Map.of("channel", "SMS", "phone", "+919876543210", "template", "delivery_otp")))
        .isInstanceOf(AppException.class);
  }

  @Test
  void missingSendServiceThrows() {
    CustomerNotificationRequestedHandler bare =
        new CustomerNotificationRequestedHandler(inApp, new ObjectMapper());
    assertThatThrownBy(
            () ->
                bare.handlePayload(
                    Map.of("channel", "SMS", "phone", "+919876543210", "template", "x")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                bare.handlePayload(
                    Map.of("channel", "WHATSAPP", "phone", "+919876543210", "template", "x")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                bare.handlePayload(Map.of("channel", "EMAIL", "email", "a@b.com", "template", "x")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void skipsIncompleteAndUnknownChannel() {
    handler.handlePayload(Map.of("channel", "SMS", "title", "t"));
    handler.handlePayload(Map.of("channel", "WHATSAPP", "title", "t"));
    handler.handlePayload(Map.of("channel", "EMAIL", "title", "t"));
    handler.handlePayload(Map.of("channel", "FAX", "title", "t", "body", "b"));
    handler.handlePayload(
        Map.of("channel", "IN_APP", "customer_id", CUST.toString(), "title", "t", "body", "b"));
    handler.handlePayload(
        Map.of(
            "channel",
            "PUSH",
            "admin_ids",
            List.of(CUST.toString(), "bad"),
            "title",
            "Page",
            "body",
            "Down",
            "recipient_type",
            "CUSTOMER",
            "data",
            Map.of("k", "v"),
            "variables",
            Map.of("n", 1)));
    verify(inApp).create(CUST, InAppNotificationType.ORDER_UPDATE, "t", "b", null);
    verify(push).send(any(PushSendService.SendCommand.class));
    verifyNoInteractions(sms, whatsapp, email);
  }

  @Test
  void handleMessageParsesEnvelope() {
    handler.handleMessage(
        """
        {"type":"customer.notification.requested","payload":{"customer_id":"%s","title":"Hi","body":"There"}}
        """
            .formatted(CUST));
    verify(inApp).create(CUST, InAppNotificationType.ORDER_UPDATE, "Hi", "There", null);
    assertThat(handler.handlePayload(null)).isNull();
    assertThat(handler.handlePayload(Map.of())).isNull();
    handler.handleMessage(null);
    handler.handleMessage(" ");
  }

  @Test
  void consumesObservabilityPages() {
    handler.handleMessage(
        """
        {"type":"observability.alert.critical_page","payload":{"alert_type":"PAYMENT_SPIKE","admin_ids":["%s"]}}
        """
            .formatted(CUST));
    verify(push).send(any(PushSendService.SendCommand.class));
    handler.handleMessage(
        """
        {"type":"observability.incident.declared","payload":{"severity":"SEV1","message":"down"}}
        """);
    handler.handleMessage("{\"type\":\"observability.alert.critical_page\",\"payload\":{}}");
    handler.handleMessage("{\"type\":\"observability.incident.declared\",\"payload\":{}}");
  }

  @Test
  void coversRecipientListsAndBlankChannels() {
    UUID pharmacy = Ids.newId();
    handler.handlePayload(
        Map.of(
            "channel",
            "PUSH",
            "pharmacy_id",
            pharmacy.toString(),
            "title",
            "PO",
            "body",
            "Sent",
            "channels",
            java.util.Arrays.asList(" ", "push"),
            "components",
            List.of("skip", Map.of("type", "body")),
            "variables",
            Map.of("a", "1")));
    verify(push).send(any(PushSendService.SendCommand.class));
    handler.handleMessage(
        """
        {"type":"medicine_schedule.notification.dose_reminder","payload":{"customer_id":"%s"}}
        """
            .formatted(CUST));
    verify(inApp)
        .create(
            CUST,
            InAppNotificationType.ORDER_UPDATE,
            "Dose reminder",
            "Time to take your medicine",
            null);
    Map<String, Object> vars = new java.util.LinkedHashMap<>();
    vars.put("n", null);
    handler.handlePayload(
        Map.of("channel", "SMS", "phone", "+919876543210", "template", "x", "variables", vars));
    verify(sms).send(any(SmsSendService.SendCommand.class));
  }

  @Test
  void coversRemainingChannelBranches() {
    handler.handlePayload(
        Map.of("customer_id", CUST.toString(), "channel", "PUSH", "title", "only"));
    handler.handlePayload(
        Map.of("customer_id", CUST.toString(), "channel", "PUSH", "body", "only"));
    handler.handlePayload(Map.of("channel", "SMS", "phone", "+919876543210"));
    handler.handlePayload(Map.of("channel", "WHATSAPP", "phone", "+919876543210"));
    handler.handlePayload(Map.of("channel", "EMAIL", "email", "a@b.com"));
    handler.handlePayload(
        Map.of(
            "channel",
            "EMAIL",
            "to_email",
            "a@b.com",
            "template",
            "INV",
            "variables",
            "not-a-map"));
    verify(email).send(any(EmailSendService.SendCommand.class));

    CustomerNotificationRequestedHandler noId =
        new CustomerNotificationRequestedHandler(
            inApp, new ObjectMapper(), push, sms, whatsapp, email, null);
    noId.handlePayload(
        Map.of("channel", "SMS", "customer_id", CUST.toString(), "template", "delivery_otp"));

    handler.handlePayload(
        "observability.alert.critical_page", Map.of("admin_ids", List.of(CUST.toString())));
    handler.handlePayload(
        "observability.incident.postmortem_reminder",
        Map.of("admin_ids", List.of(CUST.toString())));
    handler.handlePayload(
        Map.of(
            "channel",
            "PUSH",
            "customer_id",
            CUST.toString(),
            "recipient_ids",
            List.of(CUST.toString()),
            "admin_ids",
            List.of(CUST.toString()),
            "pharmacy_id",
            CUST.toString(),
            "title",
            "Dup",
            "body",
            "Ids",
            "template",
            "ORDER_CONFIRMED"));
    handler.handlePayload(
        Map.of("channel", "WHATSAPP", "phone", "+919876543210", "template", "order_rejected"));
    handler.handlePayload(
        Map.of(
            "channel",
            "WHATSAPP",
            "phone",
            "+919876543210",
            "template",
            "order_rejected",
            "components",
            List.of("skip")));
    verify(whatsapp, org.mockito.Mockito.atLeastOnce())
        .send(any(WhatsAppSendService.SendCommand.class));
  }
}

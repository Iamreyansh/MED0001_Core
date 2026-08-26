package com.nammamedmate.notification.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.webhook.WebhookRawBodyFilter;
import com.nammamedmate.notification.application.BroadcastService;
import com.nammamedmate.notification.application.DeviceTokenService;
import com.nammamedmate.notification.application.InAppNotificationService;
import com.nammamedmate.notification.application.InternalPushAuth;
import com.nammamedmate.notification.application.NotificationWebhookAuth;
import com.nammamedmate.notification.application.PreferenceService;
import com.nammamedmate.notification.application.PushSendService;
import com.nammamedmate.notification.application.SmsAdminService;
import com.nammamedmate.notification.application.SmsSendService;
import com.nammamedmate.notification.domain.NotificationUserType;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class NotificationControllersTest {

  @Mock PushSendService push;
  @Mock InternalPushAuth auth;
  @Mock NotificationWebhookAuth webhookAuth;
  @Mock DeviceTokenService tokens;
  @Mock BroadcastService broadcasts;
  @Mock SmsSendService sms;
  @Mock SmsAdminService smsAdmin;
  @Mock PreferenceService preferenceService;
  @Mock InAppNotificationService inAppNotifications;

  @InjectMocks PushSendController pushController;
  @InjectMocks CustomerDeviceTokenController customerTokens;
  @InjectMocks PharmacyDeviceTokenController pharmacyTokens;
  @InjectMocks RiderDeviceTokenController riderTokens;
  @InjectMocks AdminPushLogController adminLogs;
  @InjectMocks AdminBroadcastController adminBroadcast;
  @InjectMocks SmsSendController smsController;
  @InjectMocks AdminSmsController adminSms;
  @InjectMocks CustomerNotificationPreferencesController customerPrefs;
  @InjectMocks PharmacyNotificationPreferencesController pharmacyPrefs;
  @InjectMocks CustomerInAppNotificationController customerInApp;
  @InjectMocks AdminNotificationHistoryController adminHistory;

  private final UUID user = UUID.randomUUID();
  private final MedmatePrincipal customer =
      new MedmatePrincipal(user, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal pharmacy =
      new MedmatePrincipal(user, AuthRole.PHARMACY_STAFF, UUID.randomUUID(), TokenScope.FULL, "j");
  private final MedmatePrincipal rider =
      new MedmatePrincipal(user, AuthRole.RIDER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal admin =
      new MedmatePrincipal(user, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @Test
  void pushSendAndOpened() {
    doNothing().when(auth).require("tok");
    when(push.send(any())).thenReturn(Map.of("sent", 1));
    assertThat(pushController.send("tok", null).success()).isTrue();
    assertThat(
            pushController
                .send(
                    "tok",
                    new PushSendController.SendRequest(
                        "CUSTOMER", List.of(user), "t", "b", Map.of(), null, null, "HIGH"))
                .success())
        .isTrue();

    when(push.markOpened(any(), eq(user))).thenReturn(Map.of("opened", true));
    assertThat(pushController.opened(customer, null).success()).isTrue();
    assertThat(
            pushController.opened(customer, new PushSendController.OpenedRequest(user)).success())
        .isTrue();
  }

  @Test
  void deviceTokenControllers() {
    when(tokens.register(any(), any(), any(), any(), any())).thenReturn(Map.of("registered", true));
    when(tokens.unregister(any(), any(), any())).thenReturn(Map.of("unregistered", true));

    assertThat(customerTokens.register(customer, null).success()).isTrue();
    assertThat(
            customerTokens
                .register(
                    customer,
                    new CustomerDeviceTokenController.RegisterRequest("t", "ANDROID", "d"))
                .success())
        .isTrue();
    verify(tokens).register(user, NotificationUserType.CUSTOMER, "t", "ANDROID", "d");
    assertThat(customerTokens.unregister(customer, null).success()).isTrue();
    assertThat(
            customerTokens
                .unregister(customer, new CustomerDeviceTokenController.UnregisterRequest("d"))
                .success())
        .isTrue();

    assertThat(pharmacyTokens.register(pharmacy, null).success()).isTrue();
    assertThat(
            pharmacyTokens
                .register(
                    pharmacy, new PharmacyDeviceTokenController.RegisterRequest("t", "IOS", "d"))
                .success())
        .isTrue();
    assertThat(pharmacyTokens.unregister(pharmacy, null).success()).isTrue();
    assertThat(
            pharmacyTokens
                .unregister(pharmacy, new PharmacyDeviceTokenController.UnregisterRequest("d"))
                .success())
        .isTrue();

    assertThat(riderTokens.register(rider, null).success()).isTrue();
    assertThat(
            riderTokens
                .register(rider, new RiderDeviceTokenController.RegisterRequest("t", "IOS", "d"))
                .success())
        .isTrue();
    assertThat(riderTokens.unregister(rider, null).success()).isTrue();
    assertThat(
            riderTokens
                .unregister(rider, new RiderDeviceTokenController.UnregisterRequest("d"))
                .success())
        .isTrue();
  }

  @Test
  void adminLogsAndBroadcast() {
    when(push.listLogs(any(), any(), any(), any(), any(), any()))
        .thenReturn(new PushSendService.LogPage(Map.of("logs", List.of()), 1, 20, 0));
    assertThat(adminLogs.list(null, null, null, null, null, null).success()).isTrue();
    assertThat(
            adminLogs
                .list(
                    "CUSTOMER",
                    "SENT",
                    Instant.parse("2026-07-01T00:00:00Z"),
                    Instant.parse("2026-07-31T00:00:00Z"),
                    1,
                    20)
                .success())
        .isTrue();

    when(broadcasts.enqueue(any(), any(), any(), any(), any(), isNull()))
        .thenReturn(Map.of("status", "QUEUED"));
    assertThat(adminBroadcast.create(admin, null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(
            adminBroadcast
                .create(
                    admin,
                    new AdminBroadcastController.BroadcastRequest(
                        "ALL_CUSTOMERS", "t", "b", Map.of(), null))
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
  }

  @Test
  void smsSendWebhookAndAdmin() {
    doNothing().when(auth).require("tok");
    when(sms.send(any())).thenReturn(Map.of("status", "SENT"));
    assertThat(smsController.send("tok", null).success()).isTrue();
    assertThat(
            smsController
                .send(
                    "tok",
                    new SmsSendController.SendRequest(
                        "+919876543210", "OTP_VERIFICATION", Map.of("1", "1"), "OTP"))
                .success())
        .isTrue();

    when(sms.handleWebhook(any(), any())).thenReturn(Map.of("updated", true));
    doNothing().when(webhookAuth).requireSms(any(), any());
    MockHttpServletRequest smsReq = new MockHttpServletRequest();
    smsReq.setAttribute(WebhookRawBodyFilter.CACHED_BODY_ATTR, "{}".getBytes());
    assertThat(smsController.webhook(smsReq, "sig", null).success()).isTrue();
    assertThat(
            smsController
                .webhook(
                    smsReq,
                    "sig",
                    new SmsSendController.WebhookRequest(
                        "msg-1", null, null, Instant.parse("2026-07-24T08:20:04Z")))
                .success())
        .isTrue();
    assertThat(
            smsController
                .webhook(
                    smsReq, "sig", new SmsSendController.WebhookRequest(null, "req-1", null, null))
                .success())
        .isTrue();
    assertThat(
            smsController
                .webhook(
                    smsReq, "sig", new SmsSendController.WebhookRequest(null, null, "mid-1", null))
                .success())
        .isTrue();
    assertThat(
            smsController
                .webhook(
                    smsReq, "sig", new SmsSendController.WebhookRequest("", "req-1", null, null))
                .success())
        .isTrue();
    assertThat(
            smsController
                .webhook(
                    smsReq, "sig", new SmsSendController.WebhookRequest("  ", null, "mid-2", null))
                .success())
        .isTrue();

    when(smsAdmin.listTemplates(any(), any())).thenReturn(Map.of("templates", List.of()));
    assertThat(adminSms.listTemplates(null, true).success()).isTrue();

    when(smsAdmin.createTemplate(any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("template_id", "X"));
    assertThat(adminSms.createTemplate(admin, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(
            adminSms
                .createTemplate(
                    admin,
                    new AdminSmsController.CreateTemplateRequest(
                        "REFUND_PROCESSED", "c", "TRANSACTIONAL", "1007", "NMMATE"))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    when(smsAdmin.listLogs(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new SmsAdminService.LogPage(Map.of("logs", List.of()), 1, 20, 0));
    assertThat(adminSms.listLogs(null, null, null, null, null, null, null).success()).isTrue();
    assertThat(
            adminSms
                .listLogs(
                    "+919876543210",
                    "OTP_VERIFICATION",
                    "SENT",
                    Instant.parse("2026-07-01T00:00:00Z"),
                    Instant.parse("2026-07-31T00:00:00Z"),
                    1,
                    20)
                .success())
        .isTrue();
  }

  @Test
  void preferenceControllers() {
    when(preferenceService.getCustomerPreferences(any())).thenReturn(Map.of("customer_id", user));
    assertThat(customerPrefs.get(customer).success()).isTrue();
    when(preferenceService.patchCustomerPreferences(any(), any(), any()))
        .thenReturn(Map.of("updated", true));
    assertThat(customerPrefs.patch(customer, null).success()).isTrue();
    assertThat(
            customerPrefs
                .patch(
                    customer,
                    new CustomerNotificationPreferencesController.PatchRequest(
                        Map.of("sms", false), Map.of("offers", false)))
                .success())
        .isTrue();

    when(preferenceService.getPharmacyPreferences(any())).thenReturn(Map.of("pharmacy_id", user));
    assertThat(pharmacyPrefs.get(pharmacy).success()).isTrue();
    when(preferenceService.patchPharmacyPreferences(any(), any(), any()))
        .thenReturn(Map.of("updated", true));
    assertThat(pharmacyPrefs.patch(pharmacy, null).success()).isTrue();
    assertThat(
            pharmacyPrefs
                .patch(
                    pharmacy,
                    new PharmacyNotificationPreferencesController.PatchRequest(
                        Map.of("sms", false), Map.of("low_stock_alerts", false)))
                .success())
        .isTrue();
  }

  @Test
  void inAppNotificationControllers() {
    when(inAppNotifications.unreadCount(any())).thenReturn(Map.of("unread_count", 0L));
    assertThat(customerInApp.count(customer).success()).isTrue();

    when(inAppNotifications.list(any(), any(), any(), any(), any()))
        .thenReturn(
            new InAppNotificationService.HistoryPage(
                Map.of("notifications", List.of()), 1, 20, 0L));
    assertThat(customerInApp.list(customer, true, "PROMO", 1, 20).success()).isTrue();

    when(inAppNotifications.markAllRead(any(), any())).thenReturn(Map.of("marked_read_count", 2));
    assertThat(customerInApp.markAll(customer, null).success()).isTrue();
    assertThat(
            customerInApp
                .markAll(customer, new CustomerInAppNotificationController.MarkAllRequest(true))
                .success())
        .isTrue();

    when(inAppNotifications.markRead(any(), any()))
        .thenReturn(Map.of("id", user.toString(), "is_read", true));
    assertThat(customerInApp.read(customer, user).success()).isTrue();

    when(inAppNotifications.delete(any(), any())).thenReturn(Map.of("deleted", true));
    assertThat(customerInApp.delete(customer, user).success()).isTrue();

    InAppNotificationService.HistoryPage hist =
        new InAppNotificationService.HistoryPage(new java.util.LinkedHashMap<>(), 1, 20, 0L);
    hist.data().put("history", List.of());
    hist.data().put("export_url", null);
    when(inAppNotifications.adminHistory(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(hist);
    assertThat(
            adminHistory
                .history(
                    "SMS",
                    "FAILED",
                    "CUSTOMER",
                    Instant.parse("2026-07-01T00:00:00Z"),
                    null,
                    null,
                    1,
                    20)
                .success())
        .isTrue();
    assertThat(adminHistory.history(null, null, null, null, null, "csv", null, null).success())
        .isTrue();
  }
}

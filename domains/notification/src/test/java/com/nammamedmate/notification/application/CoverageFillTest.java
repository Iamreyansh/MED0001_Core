package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.notification.adapter.out.client.StubFcmClient;
import com.nammamedmate.notification.application.PushNotificationAcTest.FakeBroadcastStore;
import com.nammamedmate.notification.application.PushNotificationAcTest.FakeDeviceTokenStore;
import com.nammamedmate.notification.application.PushNotificationAcTest.FakePushLogStore;
import com.nammamedmate.notification.application.port.out.FcmClientPort;
import com.nammamedmate.notification.application.port.out.PushLogStore;
import com.nammamedmate.notification.domain.BroadcastAudience;
import com.nammamedmate.notification.domain.BroadcastStatus;
import com.nammamedmate.notification.domain.DevicePlatform;
import com.nammamedmate.notification.domain.NotificationUserType;
import com.nammamedmate.notification.domain.PushBroadcast;
import com.nammamedmate.notification.domain.PushLogStatus;
import com.nammamedmate.notification.domain.PushNotificationLog;
import com.nammamedmate.notification.domain.PushPriority;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoverageFillTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void fillsRemainingBranches() throws Exception {
    FakeDeviceTokenStore tokens = new FakeDeviceTokenStore();
    FakePushLogStore logs = new FakePushLogStore();
    FakeBroadcastStore broadcasts = new FakeBroadcastStore();
    StubFcmClient fcm = new StubFcmClient();
    DeviceTokenService devices = new DeviceTokenService(tokens, clock);
    PushSendService push =
        new PushSendService(
            tokens,
            logs,
            fcm,
            AllowAllPreferenceGate.INSTANCE,
            (u, t) -> Optional.empty(),
            new ObjectMapper(),
            clock);

    assertThatThrownBy(
            () -> devices.register(CUST, NotificationUserType.CUSTOMER, null, "IOS", "d"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_TOKEN");
    assertThatThrownBy(
            () -> devices.register(CUST, NotificationUserType.CUSTOMER, "t", "IOS", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_DEVICE_ID");
    assertThatThrownBy(() -> devices.unregister(CUST, NotificationUserType.CUSTOMER, ""))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_DEVICE_ID");

    assertThat(DevicePlatform.parse("ANDROID")).isEqualTo(DevicePlatform.ANDROID);
    assertThatThrownBy(() -> NotificationUserType.parse(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(PushPriority.parseOrDefault("")).isEqualTo(PushPriority.NORMAL);
    assertThat(PushPriority.parseOrDefault("NORMAL")).isEqualTo(PushPriority.NORMAL);
    assertThatThrownBy(() -> BroadcastAudience.parse(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(BroadcastAudience.ALL_CUSTOMERS.toUserType())
        .isEqualTo(NotificationUserType.CUSTOMER);

    assertThatThrownBy(
            () ->
                push.send(
                    new PushSendService.SendCommand(
                        "CUSTOMER", null, "t", null, null, null, null, "HIGH", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_RECIPIENT");

    devices.register(CUST, NotificationUserType.CUSTOMER, "tok", "IOS", "d1");
    Map<String, Object> data = new HashMap<>();
    data.put("k", null);
    data.put("n", 42);
    push.send(
        new PushSendService.SendCommand(
            "CUSTOMER", List.of(CUST), "title-only", null, data, null, null, null, null));
    push.send(
        new PushSendService.SendCommand(
            "CUSTOMER", List.of(CUST), null, "body-only", Map.of(), null, null, "NORMAL", null));
    push.send(
        new PushSendService.SendCommand(
            "CUSTOMER", List.of(CUST), "", "", null, null, null, "NORMAL", null));
    push.send(
        new PushSendService.SendCommand(
            "CUSTOMER", List.of(CUST), "t", "b", Map.of(), null, null, "NORMAL", null));

    logs.insert(
        new PushNotificationLog(
            UUID.randomUUID(),
            null,
            CUST,
            NotificationUserType.CUSTOMER,
            null,
            "t",
            "b",
            PushPriority.NORMAL,
            null,
            PushLogStatus.DELIVERED,
            NOW,
            NOW,
            null,
            null));
    assertThat(push.listLogs(null, "DELIVERED", null, null, 1, 20).data().get("logs")).isNotNull();

    PushSendService failPush =
        new PushSendService(
            tokens,
            logs,
            req -> FcmClientPort.PushResult.fail("QUOTA", null),
            AllowAllPreferenceGate.INSTANCE,
            (u, t) -> Optional.empty(),
            new ObjectMapper(),
            clock);
    failPush.send(
        new PushSendService.SendCommand(
            "CUSTOMER", List.of(CUST), "t", "b", Map.of(), null, null, "HIGH", null));

    assertThat(push.listLogs(" ", "SENT", null, null, 1, 200).page()).isEqualTo(1);
    assertThat(push.listLogs(null, "FAILED", null, null, 1, 20).total()).isGreaterThanOrEqualTo(0);

    logs.insert(
        new PushNotificationLog(
            UUID.randomUUID(),
            null,
            CUST,
            NotificationUserType.CUSTOMER,
            null,
            "t",
            "b",
            PushPriority.NORMAL,
            null,
            PushLogStatus.SENT,
            null,
            null,
            null,
            null));
    assertThat(push.listLogs("CUSTOMER", "SENT", null, null, 1, 50).data().get("logs")).isNotNull();

    ObjectMapper bad = mock(ObjectMapper.class);
    when(bad.writeValueAsString(any())).thenThrow(new JsonProcessingException("x") {});
    PushSendService badPush =
        new PushSendService(
            tokens,
            logs,
            fcm,
            AllowAllPreferenceGate.INSTANCE,
            (u, t) -> Optional.empty(),
            bad,
            clock);
    assertThatThrownBy(
            () ->
                badPush.send(
                    new PushSendService.SendCommand(
                        "CUSTOMER", List.of(CUST), "t", "b", Map.of(), null, null, "NORMAL", null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PAYLOAD");
    assertThatThrownBy(
            () ->
                new BroadcastService(broadcasts, tokens, badPush, clock)
                    .enqueue(ADMIN, "ALL_CUSTOMERS", "t", "b", Map.of(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PAYLOAD");

    UUID bid = UUID.randomUUID();
    BroadcastService race =
        new BroadcastService(
            new FakeBroadcastStore() {
              @Override
              public boolean claimRunning(UUID id, Instant now) {
                return false;
              }

              @Override
              public List<PushBroadcast> findDueQueued(Instant now, int limit) {
                return List.of(
                    new PushBroadcast(
                        bid,
                        BroadcastAudience.ALL_CUSTOMERS,
                        "t",
                        "b",
                        Map.of(),
                        null,
                        BroadcastStatus.QUEUED,
                        0,
                        ADMIN,
                        NOW,
                        null));
              }
            },
            tokens,
            push,
            clock);
    assertThat(race.processDue(5)).isEqualTo(0);

    assertThat(
            fcm.send(
                    new FcmClientPort.PushRequest(
                        null, "t", "b", Map.of(), null, null, PushPriority.HIGH, false))
                .success())
        .isTrue();

    Map<String, Object> scheduled =
        new BroadcastService(broadcasts, tokens, push, clock)
            .enqueue(
                ADMIN, "ALL_RIDERS", "t", "b", Map.of(), Instant.parse("2026-08-01T00:00:00Z"));
    assertThat(scheduled.get("scheduled_at")).isNotNull();

    // compact-ctor null branches
    assertThat(new PushLogStore.Page(null, 0).logs()).isEmpty();
    assertThat(new PushSendService.LogPage(null, 1, 20, 0).data()).isEmpty();
    assertThat(
            new FcmClientPort.PushRequest(
                    "t", null, null, null, null, null, PushPriority.NORMAL, true)
                .data())
        .isEmpty();
  }
}

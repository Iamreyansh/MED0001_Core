package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.notification.adapter.out.client.StubFcmClient;
import com.nammamedmate.notification.application.PushNotificationAcTest.FakeBroadcastStore;
import com.nammamedmate.notification.application.PushNotificationAcTest.FakeDeviceTokenStore;
import com.nammamedmate.notification.application.PushNotificationAcTest.FakePushLogStore;
import com.nammamedmate.notification.domain.BroadcastAudience;
import com.nammamedmate.notification.domain.BroadcastStatus;
import com.nammamedmate.notification.domain.NotificationUserType;
import com.nammamedmate.notification.domain.PushBroadcast;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PushSendCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");

  private FakeDeviceTokenStore tokens;
  private FakePushLogStore logs;
  private FakeBroadcastStore broadcasts;
  private StubFcmClient fcm;
  private DeviceTokenService devices;
  private PushSendService push;
  private BroadcastService broadcastService;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    tokens = new FakeDeviceTokenStore();
    logs = new FakePushLogStore();
    broadcasts = new FakeBroadcastStore();
    fcm = new StubFcmClient();
    devices = new DeviceTokenService(tokens, clock);
    push =
        new PushSendService(
            tokens,
            logs,
            fcm,
            AllowAllPreferenceGate.INSTANCE,
            (u, t) -> Optional.empty(),
            new ObjectMapper(),
            clock);
    broadcastService = new BroadcastService(broadcasts, tokens, push, clock);
  }

  @Test
  void listLogsValidationAndMarkOpened() {
    assertThatThrownBy(() -> push.listLogs("DOG", null, null, null, 0, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_RECIPIENT_TYPE");
    assertThatThrownBy(() -> push.listLogs(null, "NOPE", null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_STATUS");
    assertThat(push.listLogs(null, " ", null, null, null, null).total()).isZero();

    assertThatThrownBy(() -> push.markOpened(null, CUST))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_LOG_ID");
    assertThatThrownBy(() -> push.markOpened(UUID.randomUUID(), CUST))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("LOG_NOT_FOUND");
  }

  @Test
  void invalidRegistrationErrorAndMissingDeviceId() {
    devices.register(CUST, NotificationUserType.CUSTOMER, "bad", "ANDROID", "d1");
    fcm.markNotRegistered("bad");
    // Stub only returns NOT_REGISTERED; exercise path via custom FCM that returns
    // INVALID_REGISTRATION
    PushSendService custom =
        new PushSendService(
            tokens,
            logs,
            req -> FcmResultHelpers.invalidRegistration(),
            AllowAllPreferenceGate.INSTANCE,
            (u, t) -> Optional.empty(),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    custom.send(
        new PushSendService.SendCommand(
            "CUSTOMER", List.of(CUST), "t", "b", Map.of("a", 1), "img", "url", "HIGH", null));
    assertThat(tokens.findActiveByUser(CUST, NotificationUserType.CUSTOMER)).isEmpty();

    assertThatThrownBy(() -> devices.register(CUST, NotificationUserType.CUSTOMER, "t", "IOS", " "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_DEVICE_ID");
    assertThatThrownBy(() -> devices.unregister(CUST, NotificationUserType.CUSTOMER, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_DEVICE_ID");
  }

  @Test
  void broadcastProcessDueEmptyAndFailure() {
    assertThat(broadcastService.processDue(10)).isZero();
    broadcastService.enqueue(ADMIN, "ALL_PHARMACIES", "t", "b", null, null);
    assertThat(broadcastService.processDue(10)).isEqualTo(1);

    UUID id = UUID.randomUUID();
    broadcasts.insert(
        new PushBroadcast(
            id,
            BroadcastAudience.ALL_RIDERS,
            "t",
            "b",
            Map.of(),
            null,
            BroadcastStatus.QUEUED,
            0,
            ADMIN,
            NOW,
            null));
    BroadcastService failing =
        new BroadcastService(
            broadcasts,
            tokens,
            new PushSendService(
                tokens,
                logs,
                req -> {
                  throw new RuntimeException("boom");
                },
                AllowAllPreferenceGate.INSTANCE,
                (u, t) -> Optional.empty(),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    // Need a recipient so execute calls push.send
    devices.register(
        UUID.fromString("a0000001-0000-4000-8000-000000000099"),
        NotificationUserType.RIDER,
        "rt",
        "IOS",
        "rd");
    assertThat(failing.processDue(10)).isEqualTo(1);
    assertThat(broadcasts.findById(id).orElseThrow().status()).isEqualTo(BroadcastStatus.FAILED);
  }

  @Test
  void claimMissSkipped() {
    UUID id = UUID.randomUUID();
    broadcasts.insert(
        new PushBroadcast(
            id,
            BroadcastAudience.ALL_CUSTOMERS,
            "t",
            "b",
            Map.of(),
            null,
            BroadcastStatus.RUNNING,
            0,
            ADMIN,
            NOW,
            null));
    // findDueQueued only returns QUEUED, so zero
    assertThat(broadcastService.processDue(10)).isZero();
  }

  private static final class FcmResultHelpers {
    static com.nammamedmate.notification.application.port.out.FcmClientPort.PushResult
        invalidRegistration() {
      return com.nammamedmate.notification.application.port.out.FcmClientPort.PushResult.fail(
          "INVALID_REGISTRATION", "bad");
    }
  }
}

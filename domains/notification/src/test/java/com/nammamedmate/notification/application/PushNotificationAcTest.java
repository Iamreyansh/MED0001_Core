package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.adapter.out.client.StubFcmClient;
import com.nammamedmate.notification.application.port.out.BroadcastStore;
import com.nammamedmate.notification.application.port.out.DeviceTokenStore;
import com.nammamedmate.notification.application.port.out.PreferenceGatePort;
import com.nammamedmate.notification.application.port.out.PushLogStore;
import com.nammamedmate.notification.domain.BroadcastStatus;
import com.nammamedmate.notification.domain.DevicePlatform;
import com.nammamedmate.notification.domain.DeviceToken;
import com.nammamedmate.notification.domain.NotificationUserType;
import com.nammamedmate.notification.domain.PushBroadcast;
import com.nammamedmate.notification.domain.PushLogStatus;
import com.nammamedmate.notification.domain.PushNotificationLog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PushNotificationAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");

  private FakeDeviceTokenStore tokens;
  private FakePushLogStore logs;
  private FakeBroadcastStore broadcasts;
  private StubFcmClient fcm;
  private DeviceTokenService deviceTokens;
  private PushSendService push;
  private BroadcastService broadcastService;
  private Clock clock;

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(NOW, ZoneOffset.UTC);
    tokens = new FakeDeviceTokenStore();
    logs = new FakePushLogStore();
    broadcasts = new FakeBroadcastStore();
    fcm = new StubFcmClient();
    deviceTokens = new DeviceTokenService(tokens, clock);
    push =
        new PushSendService(
            tokens,
            logs,
            fcm,
            AllowAllPreferenceGate.INSTANCE,
            (u, t) -> Optional.of("Ravi Kumar"),
            new ObjectMapper(),
            clock);
    broadcastService = new BroadcastService(broadcasts, tokens, push, clock);
  }

  @Test
  void ac001_tokenReplaceByDeviceId() {
    deviceTokens.register(CUST, NotificationUserType.CUSTOMER, "old-token", "ANDROID", "dev-1");
    deviceTokens.register(CUST, NotificationUserType.CUSTOMER, "new-token", "ANDROID", "dev-1");
    List<DeviceToken> active = tokens.findActiveByUser(CUST, NotificationUserType.CUSTOMER);
    assertThat(active).hasSize(1);
    assertThat(active.get(0).token()).isEqualTo("new-token");
    assertThat(tokens.all().stream().filter(t -> "old-token".equals(t.token())).findFirst())
        .isEmpty();
  }

  @Test
  void registerDeactivatesSameFcmTokenOnOtherUsers() {
    UUID other = UUID.fromString("c0000002-0000-4000-8000-000000000002");
    deviceTokens.register(other, NotificationUserType.CUSTOMER, "shared-fcm", "ANDROID", "phone-a");
    deviceTokens.register(CUST, NotificationUserType.CUSTOMER, "shared-fcm", "ANDROID", "phone-b");
    assertThat(tokens.findActiveByUser(other, NotificationUserType.CUSTOMER)).isEmpty();
    assertThat(tokens.findActiveByUser(CUST, NotificationUserType.CUSTOMER)).hasSize(1);
  }

  @Test
  void ac002_deleteMarksInactiveAndNotTargeted() {
    deviceTokens.register(CUST, NotificationUserType.CUSTOMER, "tok-a", "IOS", "dev-a");
    deviceTokens.register(CUST, NotificationUserType.CUSTOMER, "tok-b", "ANDROID", "dev-b");
    deviceTokens.unregister(CUST, NotificationUserType.CUSTOMER, "dev-a");
    Map<String, Object> result =
        push.send(
            new PushSendService.SendCommand(
                "CUSTOMER", List.of(CUST), "Hi", "Body", Map.of(), null, null, "HIGH", null));
    assertThat(result.get("tokens_targeted")).isEqualTo(1);
    assertThat(logs.all()).hasSize(1);
    assertThat(logs.all().get(0).deviceTokenId())
        .isEqualTo(tokens.findActiveByUser(CUST, NotificationUserType.CUSTOMER).get(0).id());
  }

  @Test
  void ac003_payloadTooLargeBeforeFcm() {
    String big = "x".repeat(5000);
    assertThatThrownBy(
            () ->
                push.send(
                    new PushSendService.SendCommand(
                        "CUSTOMER",
                        List.of(CUST),
                        big,
                        "body",
                        Map.of(),
                        null,
                        null,
                        "NORMAL",
                        null)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAYLOAD_TOO_LARGE");
    assertThat(fcmSendCount()).isZero();
  }

  @Test
  void ac004_fcmNotRegisteredDeactivatesAndFailsLog() {
    deviceTokens.register(CUST, NotificationUserType.CUSTOMER, "dead-tok", "ANDROID", "dev-1");
    fcm.markNotRegistered("dead-tok");
    Map<String, Object> result =
        push.send(
            new PushSendService.SendCommand(
                "CUSTOMER", List.of(CUST), "t", "b", Map.of(), null, null, "HIGH", null));
    assertThat(result.get("failed")).isEqualTo(1);
    assertThat(tokens.findActiveByUser(CUST, NotificationUserType.CUSTOMER)).isEmpty();
    assertThat(logs.all().get(0).status()).isEqualTo(PushLogStatus.FAILED);
  }

  @Test
  void ac005_broadcastNullScheduleQueuedImmediately() {
    Map<String, Object> resp =
        broadcastService.enqueue(
            ADMIN, "ALL_CUSTOMERS", "Live!", "Try now", Map.of("screen", "HOME"), null);
    assertThat(resp.get("status")).isEqualTo("QUEUED");
    assertThat(resp.get("scheduled_at")).isNull();
    assertThat(broadcasts.all().get(0).status()).isEqualTo(BroadcastStatus.QUEUED);
    assertThat(broadcasts.all().get(0).scheduleAt()).isNull();
  }

  @Test
  void ac006_openedAtAfterDeepLinkClick() {
    deviceTokens.register(CUST, NotificationUserType.CUSTOMER, "tok", "IOS", "d1");
    Map<String, Object> sent =
        push.send(
            new PushSendService.SendCommand(
                "CUSTOMER",
                List.of(CUST),
                "t",
                "b",
                Map.of("screen", "ORDER"),
                null,
                "nmmedmate://order/1",
                "HIGH",
                null));
    @SuppressWarnings("unchecked")
    List<String> logIds = (List<String>) sent.get("log_ids");
    UUID logId = UUID.fromString(logIds.get(0));
    push.markOpened(logId, CUST);
    PushSendService.LogPage page = push.listLogs("CUSTOMER", null, null, null, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) page.data().get("logs");
    assertThat(rows.get(0).get("opened_at")).isEqualTo(NOW.toString());
  }

  @Test
  void ac007_threeTokensCreateThreeLogs() {
    deviceTokens.register(CUST, NotificationUserType.CUSTOMER, "t1", "IOS", "d1");
    deviceTokens.register(CUST, NotificationUserType.CUSTOMER, "t2", "ANDROID", "d2");
    deviceTokens.register(CUST, NotificationUserType.CUSTOMER, "t3", "ANDROID", "d3");
    Map<String, Object> result =
        push.send(
            new PushSendService.SendCommand(
                "CUSTOMER", List.of(CUST), "t", "b", Map.of(), null, null, "NORMAL", null));
    assertThat(result.get("tokens_targeted")).isEqualTo(3);
    assertThat(result.get("sent")).isEqualTo(3);
    @SuppressWarnings("unchecked")
    List<String> logIds = (List<String>) result.get("log_ids");
    assertThat(logIds).hasSize(3);
    assertThat(logs.all()).hasSize(3);
  }

  @Test
  void ac008_audienceResolvedAtExecutionNotSchedule() {
    deviceTokens.register(CUST, NotificationUserType.CUSTOMER, "early", "ANDROID", "d0");
    Map<String, Object> queued =
        broadcastService.enqueue(
            ADMIN,
            "ALL_CUSTOMERS",
            "Hello",
            "World",
            Map.of(),
            Instant.parse("2026-07-24T09:00:00Z"));
    UUID broadcastId = UUID.fromString((String) queued.get("broadcast_id"));
    UUID lateUser = UUID.fromString("c0000002-0000-4000-8000-000000000002");
    deviceTokens.register(lateUser, NotificationUserType.CUSTOMER, "late", "IOS", "d-late");
    int n = broadcastService.processDue(10);
    assertThat(n).isEqualTo(1);
    assertThat(broadcasts.findById(broadcastId).orElseThrow().status())
        .isEqualTo(BroadcastStatus.COMPLETED);
    assertThat(logs.all()).hasSize(2);
    assertThat(
            logs.all().stream()
                .map(PushNotificationLog::recipientUserId)
                .collect(Collectors.toSet()))
        .containsExactlyInAnyOrder(CUST, lateUser);
  }

  @Test
  void invalidRecipientTypeAndMissingRecipient() {
    assertThatThrownBy(
            () ->
                push.send(
                    new PushSendService.SendCommand(
                        "DOG", List.of(CUST), "t", "b", Map.of(), null, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_RECIPIENT_TYPE");
    assertThatThrownBy(
            () ->
                push.send(
                    new PushSendService.SendCommand(
                        "CUSTOMER", List.of(), "t", "b", Map.of(), null, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_RECIPIENT");
  }

  @Test
  void invalidAudienceAndBroadcastPayloadTooLarge() {
    assertThatThrownBy(() -> broadcastService.enqueue(ADMIN, "EVERYONE", "t", "b", Map.of(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_AUDIENCE");
    assertThatThrownBy(
            () ->
                broadcastService.enqueue(
                    ADMIN, "ALL_CUSTOMERS", "x".repeat(5000), "b", Map.of(), null))
        .extracting(ex -> ((AppException) ex).httpStatus())
        .isEqualTo(422);
  }

  @Test
  void silentPushAndPreferenceGateSkip() {
    deviceTokens.register(CUST, NotificationUserType.CUSTOMER, "tok", "IOS", "d1");
    PushSendService gated =
        new PushSendService(
            tokens,
            logs,
            fcm,
            new PreferenceGatePort() {
              @Override
              public boolean allowsPush(
                  java.util.UUID userId, NotificationUserType userType, String category) {
                return false;
              }

              @Override
              public boolean allowsSms(String toPhone, String category) {
                return true;
              }

              @Override
              public boolean allowsWhatsApp(String toPhone) {
                return true;
              }

              @Override
              public boolean allowsEmail(
                  java.util.UUID customerId, String toEmail, String category) {
                return true;
              }
            },
            (u, t) -> Optional.empty(),
            new ObjectMapper(),
            clock);
    Map<String, Object> skipped =
        gated.send(
            new PushSendService.SendCommand(
                "CUSTOMER",
                List.of(CUST),
                null,
                null,
                Map.of("sync", "1"),
                null,
                null,
                "NORMAL",
                null));
    assertThat(skipped.get("tokens_targeted")).isEqualTo(0);

    Map<String, Object> silent =
        push.send(
            new PushSendService.SendCommand(
                "CUSTOMER",
                List.of(CUST),
                null,
                null,
                Map.of("sync", "1"),
                null,
                null,
                "HIGH",
                null));
    assertThat(silent.get("sent")).isEqualTo(1);
  }

  @Test
  void deviceTokenValidation() {
    assertThatThrownBy(
            () -> deviceTokens.register(CUST, NotificationUserType.CUSTOMER, "", "ANDROID", "d1"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_TOKEN");
    assertThatThrownBy(
            () -> deviceTokens.register(CUST, NotificationUserType.CUSTOMER, "t", "WINDOWS", "d1"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PLATFORM");
  }

  private int fcmSendCount() {
    return logs.all().size();
  }

  static class FakeDeviceTokenStore implements DeviceTokenStore {
    private final Map<UUID, DeviceToken> byId = new ConcurrentHashMap<>();

    List<DeviceToken> all() {
      return new ArrayList<>(byId.values());
    }

    @Override
    public DeviceToken upsert(
        UUID userId,
        NotificationUserType userType,
        String token,
        DevicePlatform platform,
        String deviceId,
        Instant now) {
      for (DeviceToken t : List.copyOf(byId.values())) {
        if (t.active()
            && token.equals(t.token())
            && !(t.userId().equals(userId) && t.userType() == userType)) {
          byId.put(
              t.id(),
              new DeviceToken(
                  t.id(),
                  t.userId(),
                  t.userType(),
                  t.token(),
                  t.platform(),
                  t.deviceId(),
                  false,
                  t.registeredAt(),
                  now));
        }
      }
      Optional<DeviceToken> existing = findByUserAndDevice(userId, userType, deviceId);
      if (existing.isPresent()) {
        DeviceToken prev = existing.get();
        DeviceToken updated =
            new DeviceToken(
                prev.id(),
                userId,
                userType,
                token,
                platform,
                deviceId,
                true,
                prev.registeredAt(),
                now);
        byId.put(updated.id(), updated);
        return updated;
      }
      DeviceToken row =
          new DeviceToken(Ids.newId(), userId, userType, token, platform, deviceId, true, now, now);
      byId.put(row.id(), row);
      return row;
    }

    @Override
    public Optional<DeviceToken> findByUserAndDevice(
        UUID userId, NotificationUserType userType, String deviceId) {
      return byId.values().stream()
          .filter(
              t ->
                  t.userId().equals(userId)
                      && t.userType() == userType
                      && t.deviceId().equals(deviceId))
          .findFirst();
    }

    @Override
    public boolean deactivate(
        UUID userId, NotificationUserType userType, String deviceId, Instant now) {
      Optional<DeviceToken> existing = findByUserAndDevice(userId, userType, deviceId);
      if (existing.isEmpty()) {
        return false;
      }
      DeviceToken t = existing.get();
      byId.put(
          t.id(),
          new DeviceToken(
              t.id(),
              t.userId(),
              t.userType(),
              t.token(),
              t.platform(),
              t.deviceId(),
              false,
              t.registeredAt(),
              now));
      return true;
    }

    @Override
    public void deactivateById(UUID tokenId, Instant now) {
      DeviceToken t = byId.get(tokenId);
      if (t == null) {
        return;
      }
      byId.put(
          tokenId,
          new DeviceToken(
              t.id(),
              t.userId(),
              t.userType(),
              t.token(),
              t.platform(),
              t.deviceId(),
              false,
              t.registeredAt(),
              now));
    }

    @Override
    public List<DeviceToken> findActiveByUser(UUID userId, NotificationUserType userType) {
      return byId.values().stream()
          .filter(t -> t.userId().equals(userId) && t.userType() == userType && t.active())
          .toList();
    }

    @Override
    public List<DeviceToken> findActiveByUserType(NotificationUserType userType) {
      return byId.values().stream().filter(t -> t.userType() == userType && t.active()).toList();
    }

    @Override
    public int countActiveByUserType(NotificationUserType userType) {
      return findActiveByUserType(userType).size();
    }
  }

  static class FakePushLogStore implements PushLogStore {
    private final Map<UUID, PushNotificationLog> byId = new LinkedHashMap<>();

    List<PushNotificationLog> all() {
      return new ArrayList<>(byId.values());
    }

    @Override
    public void insert(PushNotificationLog log) {
      byId.put(log.id(), log);
    }

    @Override
    public Optional<PushNotificationLog> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public boolean markOpened(UUID logId, UUID recipientUserId, Instant openedAt) {
      PushNotificationLog log = byId.get(logId);
      if (log == null || !log.recipientUserId().equals(recipientUserId)) {
        return false;
      }
      byId.put(
          logId,
          new PushNotificationLog(
              log.id(),
              log.broadcastId(),
              log.recipientUserId(),
              log.recipientType(),
              log.deviceTokenId(),
              log.title(),
              log.body(),
              log.priority(),
              log.fcmMessageId(),
              log.status(),
              log.sentAt(),
              log.deliveredAt(),
              openedAt,
              log.errorMessage()));
      return true;
    }

    @Override
    public Page list(ListFilter filter) {
      List<PushNotificationLog> filtered =
          byId.values().stream()
              .filter(
                  l ->
                      filter.recipientType() == null || l.recipientType() == filter.recipientType())
              .filter(l -> filter.status() == null || l.status() == filter.status())
              .toList();
      return new Page(filtered, filtered.size());
    }
  }

  static class FakeBroadcastStore implements BroadcastStore {
    private final Map<UUID, PushBroadcast> byId = new LinkedHashMap<>();

    List<PushBroadcast> all() {
      return new ArrayList<>(byId.values());
    }

    @Override
    public PushBroadcast insert(PushBroadcast broadcast) {
      byId.put(broadcast.id(), broadcast);
      return broadcast;
    }

    @Override
    public Optional<PushBroadcast> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<PushBroadcast> findDueQueued(Instant now, int limit) {
      return byId.values().stream()
          .filter(b -> b.status() == BroadcastStatus.QUEUED)
          .filter(b -> b.scheduleAt() == null || !b.scheduleAt().isAfter(now))
          .limit(limit)
          .toList();
    }

    @Override
    public boolean claimRunning(UUID id, Instant now) {
      PushBroadcast b = byId.get(id);
      if (b == null || b.status() != BroadcastStatus.QUEUED) {
        return false;
      }
      byId.put(
          id,
          new PushBroadcast(
              b.id(),
              b.audience(),
              b.title(),
              b.body(),
              b.data(),
              b.scheduleAt(),
              BroadcastStatus.RUNNING,
              b.estimatedRecipients(),
              b.createdBy(),
              b.createdAt(),
              b.executedAt()));
      return true;
    }

    @Override
    public void updateStatus(
        UUID id, BroadcastStatus status, Instant executedAt, Integer estimatedRecipients) {
      PushBroadcast b = byId.get(id);
      if (b == null) {
        return;
      }
      byId.put(
          id,
          new PushBroadcast(
              b.id(),
              b.audience(),
              b.title(),
              b.body(),
              b.data(),
              b.scheduleAt(),
              status,
              estimatedRecipients == null ? b.estimatedRecipients() : estimatedRecipients,
              b.createdBy(),
              b.createdAt(),
              executedAt == null ? b.executedAt() : executedAt));
    }
  }
}

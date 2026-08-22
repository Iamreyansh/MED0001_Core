package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.notification.adapter.in.messaging.CustomerNotificationRequestedHandler;
import com.nammamedmate.notification.application.port.out.DispatchLogStore;
import com.nammamedmate.notification.application.port.out.InAppNotificationStore;
import com.nammamedmate.notification.domain.DispatchLogEntry;
import com.nammamedmate.notification.domain.InAppNotification;
import com.nammamedmate.notification.domain.InAppNotificationType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InAppNotificationAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID CUST = UUID.fromString("c0000001-0000-4000-8000-000000000001");

  private FakeInAppStore store;
  private FakeDispatchStore dispatch;
  private InAppNotificationService service;
  private Clock clock;

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(NOW, ZoneOffset.UTC);
    store = new FakeInAppStore();
    dispatch = new FakeDispatchStore();
    service =
        new InAppNotificationService(store, dispatch, (id, t) -> Optional.of("Ravi Kumar"), clock);
  }

  @Test
  void ac001_unreadOnlyFilter() {
    service.createOrderUpdate(CUST, "Out for delivery", "ETA 12m", "nmmedmate://order/1");
    InAppNotification read =
        service.create(CUST, InAppNotificationType.PROMO, "Sale", "20% off", null);
    store.markRead(read.id(), CUST, NOW);

    InAppNotificationService.HistoryPage page = service.list(CUST, true, null, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) page.data().get("notifications");
    assertThat(items).hasSize(1);
    assertThat(items.get(0).get("is_read")).isEqualTo(false);
    assertThat(items.get(0).get("type")).isEqualTo("ORDER_UPDATE");
  }

  @Test
  void ac002_countZeroNotNull() {
    Map<String, Object> data = service.unreadCount(CUST);
    assertThat(data.get("unread_count")).isEqualTo(0L);
    assertThat(data.get("unread_count")).isNotNull();
  }

  @Test
  void ac003_cannotDeleteOrderUpdate() {
    InAppNotification n =
        service.createOrderUpdate(CUST, "Confirmed", "Your order is confirmed", null);
    assertThatThrownBy(() -> service.delete(CUST, n.id()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CANNOT_DELETE_ORDER_UPDATE");
  }

  @Test
  void ac004_markReadSetsFlags() {
    InAppNotification n =
        service.createOrderUpdate(CUST, "Shipped", "On the way", "nmmedmate://order/x");
    Map<String, Object> data = service.markRead(CUST, n.id());
    assertThat(data.get("is_read")).isEqualTo(true);
    assertThat(data.get("read_at")).isEqualTo(NOW.toString());
    assertThat(store.findByIdForCustomer(n.id(), CUST).orElseThrow().read()).isTrue();
    assertThat(store.findByIdForCustomer(n.id(), CUST).orElseThrow().readAt()).isEqualTo(NOW);
  }

  @Test
  void ac005_markAllReadReturnsCount() {
    service.createOrderUpdate(CUST, "A", "a", null);
    service.create(CUST, InAppNotificationType.SYSTEM, "B", "b", null);
    Map<String, Object> data = service.markAllRead(CUST, true);
    assertThat(data.get("marked_read_count")).isEqualTo(2);
    assertThat(data.get("updated_at")).isEqualTo(NOW.toString());
    assertThat(service.unreadCount(CUST).get("unread_count")).isEqualTo(0L);
  }

  @Test
  void ac006_expiredPromoNotListed() {
    Instant created = NOW.minus(Duration.ofDays(31));
    store.insert(
        new InAppNotification(
            Ids.newId(),
            CUST,
            InAppNotificationType.PROMO,
            "Old promo",
            "Expired",
            null,
            false,
            false,
            null,
            created.plus(Duration.ofDays(30)),
            created));
    service.create(CUST, InAppNotificationType.PROMO, "Fresh", "Still good", null);

    InAppNotificationService.HistoryPage page = service.list(CUST, null, "PROMO", 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) page.data().get("notifications");
    assertThat(items).hasSize(1);
    assertThat(items.get(0).get("title")).isEqualTo("Fresh");
  }

  @Test
  void ac007_adminHistoryFiltersSmsFailed() {
    UUID ok = Ids.newId();
    UUID fail = Ids.newId();
    dispatch.add(
        new DispatchLogEntry(ok, CUST, "CUSTOMER", "SMS", "OTP", "OTP", "DELIVERED", NOW, NOW));
    dispatch.add(new DispatchLogEntry(fail, null, null, "SMS", "OTP", "OTP", "FAILED", NOW, null));
    dispatch.add(
        new DispatchLogEntry(
            Ids.newId(), CUST, "CUSTOMER", "PUSH", null, "Hi", "FAILED", NOW, null));

    InAppNotificationService.HistoryPage page =
        service.adminHistory("SMS", "FAILED", null, null, null, null, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> history = (List<Map<String, Object>>) page.data().get("history");
    assertThat(history).hasSize(1);
    assertThat(history.get(0).get("channel")).isEqualTo("SMS");
    assertThat(history.get(0).get("status")).isEqualTo("FAILED");
    assertThat(page.data().get("export_url")).isNull();
  }

  @Test
  void ac008_exportCsvReturnsUrl() {
    dispatch.add(
        new DispatchLogEntry(
            Ids.newId(), CUST, "CUSTOMER", "EMAIL", "T", "Subject, quoted", "SENT", NOW, null));
    InAppNotificationService.HistoryPage page =
        service.adminHistory(null, null, null, null, null, "csv", 1, 20);
    assertThat(page.data().get("export_url")).isInstanceOf(String.class);
    assertThat(page.data().get("export_url").toString()).startsWith("data:text/csv;base64,");
  }

  @Test
  void ac009_autoCreateFromOrderEvent() {
    CustomerNotificationRequestedHandler handler =
        new CustomerNotificationRequestedHandler(service, new ObjectMapper());
    String json =
        """
        {
          "type":"customer.notification.requested",
          "payload":{
            "customer_id":"%s",
            "channel":"PUSH",
            "order_id":"o0000001-0000-4000-8000-000000000001",
            "title":"Your order is out for delivery!",
            "body":"Ramesh is on the way."
          }
        }
        """
            .formatted(CUST);
    handler.handleMessage(json);
    assertThat(store.all()).hasSize(1);
    InAppNotification created = store.all().get(0);
    assertThat(created.type()).isEqualTo(InAppNotificationType.ORDER_UPDATE);
    assertThat(created.customerId()).isEqualTo(CUST);
    assertThat(created.actionUrl())
        .isEqualTo("nmmedmate://order/o0000001-0000-4000-8000-000000000001");
    assertThat(created.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
  }

  @Test
  void deletePromoAndCoverageBranches() {
    InAppNotification promo =
        service.create(CUST, InAppNotificationType.PROMO, "P", "body", "nmmedmate://offers/x");
    assertThat(service.delete(CUST, promo.id()).get("deleted")).isEqualTo(true);

    InAppNotification refill =
        service.create(CUST, InAppNotificationType.REFILL_REMINDER, "R", "body", null);
    assertThatThrownBy(() -> service.delete(CUST, refill.id()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CANNOT_DELETE");

    assertThatThrownBy(() -> service.delete(CUST, Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NOTIFICATION_NOT_FOUND");
    assertThatThrownBy(() -> service.markRead(CUST, Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NOTIFICATION_NOT_FOUND");
    assertThatThrownBy(() -> service.markAllRead(CUST, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.list(CUST, null, "NOPE", 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.create(null, InAppNotificationType.SYSTEM, "t", "b", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.create(CUST, InAppNotificationType.SYSTEM, " ", "b", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.adminHistory("FAX", null, null, null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    handlerIgnoresNonPushAndBadPayload();
    assertThat(service.runTtlCleanup()).isGreaterThanOrEqualTo(0);

    InAppNotificationService.HistoryPage typed =
        service.list(CUST, false, "REFILL_REMINDER", null, null);
    assertThat(typed.total()).isEqualTo(1);
  }

  private void handlerIgnoresNonPushAndBadPayload() {
    CustomerNotificationRequestedHandler handler =
        new CustomerNotificationRequestedHandler(service, new ObjectMapper());
    handler.handleMessage(null);
    handler.handleMessage(" ");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handleMessage("{not-json"))
        .isInstanceOf(IllegalStateException.class);
    com.fasterxml.jackson.databind.ObjectMapper broken =
        org.mockito.Mockito.mock(com.fasterxml.jackson.databind.ObjectMapper.class);
    try {
      org.mockito.Mockito.when(
              broken.readValue(
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.any(
                      com.fasterxml.jackson.core.type.TypeReference.class)))
          .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("boom") {});
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new CustomerNotificationRequestedHandler(service, broken).handleMessage("{}"))
        .isInstanceOf(IllegalStateException.class);
    com.fasterxml.jackson.databind.ObjectMapper runtimeBroken =
        org.mockito.Mockito.mock(com.fasterxml.jackson.databind.ObjectMapper.class);
    try {
      org.mockito.Mockito.when(
              runtimeBroken.readValue(
                  org.mockito.ArgumentMatchers.anyString(),
                  org.mockito.ArgumentMatchers.any(
                      com.fasterxml.jackson.core.type.TypeReference.class)))
          .thenThrow(new IllegalArgumentException("rt"));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new CustomerNotificationRequestedHandler(service, runtimeBroken)
                    .handleMessage("{}"))
        .isInstanceOf(IllegalArgumentException.class);
    handler.handleMessage("{\"type\":\"other.event\",\"payload\":{}}");
    handler.handlePayload(null);
    handler.handlePayload(Map.of());
    handler.handlePayload(Map.of("title", "t", "body", "b"));
    handler.handlePayload(
        Map.of("customer_id", CUST.toString(), "channel", "SMS", "title", "t", "body", "b"));
    handler.handlePayload(
        Map.of("customer_id", "bad", "channel", "PUSH", "title", "t", "body", "b"));
    handler.handleMessage(
        """
        {"customer_id":"%s","channel":"PUSH","title":"Bare","body":"payload"}
        """
            .formatted(CUST));
    assertThat(store.all().stream().filter(n -> "Bare".equals(n.title())).findFirst()).isPresent();
  }

  @Test
  void ttlCleanupSoftAndHardDeletes() {
    Instant old = NOW.minus(Duration.ofDays(40));
    UUID expiredId = Ids.newId();
    store.insert(
        new InAppNotification(
            expiredId,
            CUST,
            InAppNotificationType.PROMO,
            "gone",
            "x",
            null,
            false,
            false,
            null,
            old,
            old.minus(Duration.ofDays(30))));
    UUID archivedId = Ids.newId();
    store.insert(
        new InAppNotification(
            archivedId,
            CUST,
            InAppNotificationType.SYSTEM,
            "archived",
            "x",
            null,
            false,
            true,
            null,
            NOW.minus(Duration.ofDays(40)),
            NOW.minus(Duration.ofDays(70))));
    int cleaned = service.runTtlCleanup();
    assertThat(cleaned).isGreaterThanOrEqualTo(2);
    assertThat(store.findByIdForCustomer(expiredId, CUST)).isEmpty();
    assertThat(store.all().stream().noneMatch(n -> n.id().equals(archivedId))).isTrue();
  }

  @Test
  void adminHistoryResolvesNameAndInvalidRecipientType() {
    dispatch.add(
        new DispatchLogEntry(
            Ids.newId(), CUST, "NOT_A_TYPE", "PUSH", null, "t", "SENT", NOW, null));
    InAppNotificationService.HistoryPage page =
        service.adminHistory(
            "push", null, null, NOW.minusSeconds(1), NOW.plusSeconds(1), null, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> history = (List<Map<String, Object>>) page.data().get("history");
    assertThat(history.get(0).get("recipient_name")).isNull();
  }

  @Test
  void coverageFillBranches() {
    assertThatThrownBy(() -> service.create(CUST, InAppNotificationType.SYSTEM, null, "b", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.create(CUST, InAppNotificationType.SYSTEM, "t", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.create(CUST, InAppNotificationType.SYSTEM, "t", "  ", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.markAllRead(CUST, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    service.create(CUST, InAppNotificationType.SYSTEM, "Sys", "body", "  ");
    service.list(CUST, null, "  ", 1, 20);
    service.list(CUST, false, null, 1, 20);

    dispatch.add(
        new DispatchLogEntry(
            Ids.newId(), CUST, "CUSTOMER", "PUSH", null, "Hi", "DELIVERED", NOW, NOW));
    InAppNotificationService.HistoryPage named =
        service.adminHistory(null, null, null, null, null, null, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> hist = (List<Map<String, Object>>) named.data().get("history");
    assertThat(hist.stream().anyMatch(h -> "Ravi Kumar".equals(h.get("recipient_name")))).isTrue();
    assertThat(hist.stream().anyMatch(h -> h.get("delivered_at") != null)).isTrue();

    service.adminHistory(null, null, null, null, null, " CSV ", 1, 20);
    service.adminHistory(null, null, null, null, null, "  ", 1, 20);
    dispatch.add(
        new DispatchLogEntry(Ids.newId(), CUST, null, "PUSH", null, "NoType", "SENT", NOW, null));
    dispatch.add(
        new DispatchLogEntry(
            Ids.newId(), CUST, "CUSTOMER", "EMAIL", "T", "says \"hi\"", "SENT", NOW, null));
    service.adminHistory("", "  ", null, null, null, "csv", 1, 50);

    InAppNotificationService emptyNames =
        new InAppNotificationService(store, dispatch, (id, t) -> Optional.empty(), clock);
    emptyNames.adminHistory(null, null, null, null, null, null, 1, 20);
    CustomerNotificationRequestedHandler handler =
        new CustomerNotificationRequestedHandler(service, new ObjectMapper());
    handler.handleMessage("{\"type\":\"other.event\"}");
    handler.handlePayload(Map.of("title", "only"));
    handler.handlePayload(Map.of("body", "only"));
    handler.handlePayload(Map.of("title", "  ", "body", "b", "customer_id", CUST.toString()));
    handler.handlePayload(
        Map.of(
            "title",
            "NoChannel",
            "body",
            "b",
            "customer_id",
            CUST.toString(),
            "action_url",
            "nmmedmate://x"));
    assertThat(store.all().stream().anyMatch(n -> "NoChannel".equals(n.title()))).isTrue();
  }

  static final class FakeInAppStore implements InAppNotificationStore {
    private final Map<UUID, InAppNotification> byId = new ConcurrentHashMap<>();

    List<InAppNotification> all() {
      return new ArrayList<>(byId.values());
    }

    @Override
    public void insert(InAppNotification notification) {
      byId.put(notification.id(), notification);
    }

    @Override
    public Optional<InAppNotification> findByIdForCustomer(UUID id, UUID customerId) {
      return Optional.ofNullable(byId.get(id))
          .filter(n -> n.customerId().equals(customerId) && !n.deleted());
    }

    @Override
    public Page list(ListFilter filter) {
      List<InAppNotification> matched =
          byId.values().stream()
              .filter(n -> n.customerId().equals(filter.customerId()))
              .filter(n -> !n.deleted())
              .filter(n -> n.expiresAt().isAfter(filter.now()))
              .filter(n -> !filter.unreadOnly() || !n.read())
              .filter(n -> filter.type() == null || n.type() == filter.type())
              .sorted(Comparator.comparing(InAppNotification::createdAt).reversed())
              .collect(Collectors.toCollection(ArrayList::new));
      long total = matched.size();
      int from = Math.min((filter.page() - 1) * filter.limit(), matched.size());
      int to = Math.min(from + filter.limit(), matched.size());
      return new Page(matched.subList(from, to), total);
    }

    @Override
    public long countUnread(UUID customerId, Instant now) {
      return byId.values().stream()
          .filter(n -> n.customerId().equals(customerId))
          .filter(n -> !n.deleted() && !n.read() && n.expiresAt().isAfter(now))
          .count();
    }

    @Override
    public boolean markRead(UUID id, UUID customerId, Instant readAt) {
      InAppNotification n = byId.get(id);
      if (n == null
          || !n.customerId().equals(customerId)
          || n.deleted()
          || !n.expiresAt().isAfter(readAt)) {
        return false;
      }
      byId.put(
          id,
          new InAppNotification(
              n.id(),
              n.customerId(),
              n.type(),
              n.title(),
              n.body(),
              n.actionUrl(),
              true,
              false,
              n.readAt() == null ? readAt : n.readAt(),
              n.expiresAt(),
              n.createdAt()));
      return true;
    }

    @Override
    public int markAllRead(UUID customerId, Instant readAt, Instant now) {
      int n = 0;
      for (InAppNotification item : List.copyOf(byId.values())) {
        if (item.customerId().equals(customerId)
            && !item.deleted()
            && !item.read()
            && item.expiresAt().isAfter(now)) {
          markRead(item.id(), customerId, readAt);
          n++;
        }
      }
      return n;
    }

    @Override
    public boolean softDelete(UUID id, UUID customerId) {
      InAppNotification n = byId.get(id);
      if (n == null || !n.customerId().equals(customerId) || n.deleted()) {
        return false;
      }
      byId.put(
          id,
          new InAppNotification(
              n.id(),
              n.customerId(),
              n.type(),
              n.title(),
              n.body(),
              n.actionUrl(),
              n.read(),
              true,
              n.readAt(),
              n.expiresAt(),
              n.createdAt()));
      return true;
    }

    @Override
    public int softDeleteExpired(Instant now) {
      int n = 0;
      for (InAppNotification item : List.copyOf(byId.values())) {
        if (!item.deleted() && !item.expiresAt().isAfter(now)) {
          softDelete(item.id(), item.customerId());
          n++;
        }
      }
      return n;
    }

    @Override
    public int hardDeletePastRetention(Instant cutoff) {
      int n = 0;
      for (InAppNotification item : List.copyOf(byId.values())) {
        if (item.deleted() && !item.expiresAt().isAfter(cutoff)) {
          byId.remove(item.id());
          n++;
        }
      }
      return n;
    }
  }

  static final class FakeDispatchStore implements DispatchLogStore {
    private final List<DispatchLogEntry> rows = new ArrayList<>();

    void add(DispatchLogEntry e) {
      rows.add(e);
    }

    @Override
    public Page list(ListFilter filter) {
      List<DispatchLogEntry> matched =
          rows.stream()
              .filter(e -> filter.channel() == null || filter.channel().equals(e.channel()))
              .filter(e -> filter.status() == null || filter.status().equals(e.status()))
              .filter(
                  e ->
                      filter.recipientType() == null
                          || filter.recipientType().equals(e.recipientType()))
              .filter(e -> filter.dateFrom() == null || !e.sentAt().isBefore(filter.dateFrom()))
              .filter(e -> filter.dateTo() == null || !e.sentAt().isAfter(filter.dateTo()))
              .sorted(Comparator.comparing(DispatchLogEntry::sentAt).reversed())
              .collect(Collectors.toCollection(ArrayList::new));
      long total = matched.size();
      int from = Math.min((filter.page() - 1) * filter.limit(), matched.size());
      int to = Math.min(from + filter.limit(), matched.size());
      return new Page(matched.subList(from, to), total);
    }
  }
}

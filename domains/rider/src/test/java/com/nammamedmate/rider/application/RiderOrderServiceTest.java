package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.adapter.out.cache.RedisAssignmentOtpCache;
import com.nammamedmate.rider.adapter.out.client.StubDistanceMatrixAdapter;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort.OrderDetails;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort.QueuePage;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore.AssignmentRecord;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore.EarningsRecord;
import com.nammamedmate.rider.domain.AssignmentOtps;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiderOrderServiceTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T09:08:00Z"), ZoneOffset.UTC);
  private final UUID riderId = Ids.newId();
  private final UUID otherRider = Ids.newId();
  private final UUID orderId = Ids.newId();
  private final UUID zoneId = Ids.newId();

  private FakeAssignments assignments;
  private FakeOrders orders;
  private RedisAssignmentOtpCache otpCache;
  private FakeEarnings earnings;
  private RiderOrderService service;
  private String pickupOtp;
  private String deliveryOtp;

  @BeforeEach
  void setUp() {
    assignments = new FakeAssignments();
    orders = new FakeOrders();
    otpCache = new RedisAssignmentOtpCache(null);
    earnings = new FakeEarnings();
    pickupOtp = "7821";
    deliveryOtp = "3942";
    orders.put(order(orderId, "READY_FOR_PICKUP", riderId));
    orders.deliveryOtps.put(orderId, deliveryOtp);
    AssignmentRecord pending =
        new AssignmentRecord(
            Ids.newId(),
            orderId,
            riderId,
            "MANUAL",
            Ids.newId(),
            "PENDING_ACCEPTANCE",
            Instant.parse("2026-07-24T09:12:00Z"),
            null,
            null,
            null,
            AssignmentOtps.hash(pickupOtp),
            AssignmentOtps.hash(deliveryOtp),
            null,
            BigDecimal.valueOf(80),
            Instant.parse("2026-07-24T09:07:00Z"),
            Instant.parse("2026-07-24T09:07:00Z"));
    assignments.insert(pending);
    otpCache.storePickupOtp(orderId, pickupOtp);
    otpCache.storeDeliveryOtp(orderId, deliveryOtp);
    otpCache.resetPickupAttempts(orderId);
    service =
        new RiderOrderService(
            assignments,
            orders,
            otpCache,
            new StubDistanceMatrixAdapter(),
            earnings,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            clock);
  }

  @Test
  void ac003_expiredAcceptReturns410() {
    AssignmentRecord a = assignments.findActiveByOrder(orderId).orElseThrow();
    assignments.update(
        new AssignmentRecord(
            a.id(),
            a.orderId(),
            a.riderId(),
            a.assignmentType(),
            a.assignedBy(),
            a.status(),
            Instant.parse("2026-07-24T09:00:00Z"),
            null,
            null,
            null,
            a.pickupOtpHash(),
            a.deliveryOtpHash(),
            null,
            a.compositeScore(),
            a.createdAt(),
            a.updatedAt()));
    assertThatThrownBy(() -> service.accept(rider(), orderId))
        .isInstanceOf(AppException.class)
        .satisfies(
            e -> {
              AppException ex = (AppException) e;
              assertThat(ex.code()).isEqualTo("ASSIGNMENT_EXPIRED");
              assertThat(ex.httpStatus()).isEqualTo(410);
            });
  }

  @Test
  void ac004_pickupConfirmAdvancesAndRejectsBadOtp() {
    service.accept(rider(), orderId);
    assertThatThrownBy(() -> service.pickupConfirm(rider(), orderId, "0000"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PICKUP_OTP");
    Map<String, Object> ok = service.pickupConfirm(rider(), orderId, pickupOtp);
    assertThat(ok.get("order_status")).isEqualTo("OUT_FOR_DELIVERY");
    assertThat(orders.findOrder(orderId).orElseThrow().status()).isEqualTo("OUT_FOR_DELIVERY");
  }

  @Test
  void ac005_deliverCreatesEarningsAndOnTimeFlag() {
    service.accept(rider(), orderId);
    service.pickupConfirm(rider(), orderId, pickupOtp);
    Map<String, Object> result = service.deliver(rider(), orderId, deliveryOtp);
    assertThat(result.get("order_status")).isEqualTo("DELIVERED");
    assertThat(result.get("on_time")).isEqualTo(true);
    assertThat(earnings.rows).hasSize(1);
    assertThat(earnings.rows.get(0).basePayPaise()).isBetween(1500L, 2500L);
    assertThat(earnings.rows.get(0).deliveryDate()).isNotNull();
    assertThatThrownBy(() -> service.deliver(rider(), orderId, deliveryOtp))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");
  }

  @Test
  void currentAndNotYourOrderAndAlreadyAccepted() {
    Map<String, Object> before = service.current(rider());
    assertThat(before.get("assignment_status")).isEqualTo("PENDING_ACCEPTANCE");
    assertThat(before).doesNotContainKey("pickup_otp");
    assertThat(((Map<?, ?>) before.get("delivery"))).isEmpty();
    Map<String, Object> accepted = service.accept(rider(), orderId);
    assertThat(accepted).doesNotContainKey("pickup_otp");
    assertThat(service.current(rider())).doesNotContainKey("pickup_otp");
    assertThatThrownBy(() -> service.accept(rider(), orderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ALREADY_ACCEPTED");
    assertThatThrownBy(() -> service.accept(other(), orderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NOT_YOUR_ORDER");
  }

  @Test
  void invalidDeliveryOtp() {
    service.accept(rider(), orderId);
    service.pickupConfirm(rider(), orderId, pickupOtp);
    assertThatThrownBy(() -> service.deliver(rider(), orderId, "1111"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DELIVERY_OTP");
  }

  @Test
  void deliverUsesCanonicalConfirmPort() {
    java.util.concurrent.atomic.AtomicBoolean confirmed =
        new java.util.concurrent.atomic.AtomicBoolean();
    service.setDeliveryConfirm((oid, rid, at) -> confirmed.set(true));
    service.accept(rider(), orderId);
    service.pickupConfirm(rider(), orderId, pickupOtp);
    Map<String, Object> result = service.deliver(rider(), orderId, deliveryOtp);
    assertThat(result.get("order_status")).isEqualTo("DELIVERED");
    assertThat(confirmed).isTrue();
  }

  @Test
  void currentAcceptDeliverWhenAssignmentOrOrderMissing() {
    assertThatThrownBy(
            () ->
                service.current(
                    new MedmatePrincipal(Ids.newId(), AuthRole.RIDER, null, TokenScope.FULL, "j")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");

    orders.byId.clear();
    assertThatThrownBy(() -> service.current(rider()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");
    assertThatThrownBy(() -> service.accept(rider(), orderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");

    orders.put(order(orderId, "READY_FOR_PICKUP", riderId));
    service.accept(rider(), orderId);
    service.pickupConfirm(rider(), orderId, pickupOtp);
    orders.byId.clear();
    assertThatThrownBy(() -> service.deliver(rider(), orderId, deliveryOtp))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");
  }

  private MedmatePrincipal rider() {
    return new MedmatePrincipal(riderId, AuthRole.RIDER, null, TokenScope.FULL, "j");
  }

  private MedmatePrincipal other() {
    return new MedmatePrincipal(otherRider, AuthRole.RIDER, null, TokenScope.FULL, "j");
  }

  private OrderDetails order(UUID id, String status, UUID rider) {
    return new OrderDetails(
        id,
        "MED-1",
        status,
        rider,
        Ids.newId(),
        "Apollo",
        "Koramangala",
        12.93,
        77.62,
        "9900112233",
        zoneId,
        "Koramangala",
        "Priya",
        "9876501234",
        "HSR",
        12.91,
        77.63,
        3,
        "UPI",
        45000,
        Instant.parse("2026-07-24T09:40:00Z"),
        Instant.parse("2026-07-24T09:40:00Z"),
        AssignmentOtps.hash(deliveryOtp));
  }

  static final class FakeAssignments implements OrderAssignmentStore {
    final Map<UUID, AssignmentRecord> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(AssignmentRecord row) {
      byId.put(row.id(), row);
    }

    @Override
    public void update(AssignmentRecord row) {
      byId.put(row.id(), row);
    }

    @Override
    public Optional<AssignmentRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<AssignmentRecord> findActiveByOrder(UUID orderId) {
      return byId.values().stream()
          .filter(a -> a.orderId().equals(orderId))
          .filter(a -> List.of("PENDING_ACCEPTANCE", "ACCEPTED", "PICKED_UP").contains(a.status()))
          .findFirst();
    }

    @Override
    public Optional<AssignmentRecord> findCurrentForRider(UUID riderId) {
      return byId.values().stream()
          .filter(a -> a.riderId().equals(riderId))
          .filter(a -> List.of("PENDING_ACCEPTANCE", "ACCEPTED", "PICKED_UP").contains(a.status()))
          .findFirst();
    }

    @Override
    public int countActiveForRider(UUID riderId) {
      return (int)
          byId.values().stream()
              .filter(a -> a.riderId().equals(riderId))
              .filter(
                  a -> List.of("PENDING_ACCEPTANCE", "ACCEPTED", "PICKED_UP").contains(a.status()))
              .count();
    }

    @Override
    public List<AssignmentRecord> findPendingPastDeadline(Instant now, int limit) {
      return List.of();
    }

    @Override
    public boolean hasActiveForOrder(UUID orderId) {
      return findActiveByOrder(orderId).isPresent();
    }
  }

  static final class FakeOrders implements DispatchOrderPort {
    final Map<UUID, OrderDetails> byId = new ConcurrentHashMap<>();
    final Map<UUID, String> deliveryOtps = new ConcurrentHashMap<>();

    void put(OrderDetails o) {
      byId.put(o.orderId(), o);
    }

    @Override
    public QueuePage listUnassignedReady(UUID zoneId, int page, int limit) {
      return new QueuePage(List.of(), 0);
    }

    @Override
    public Optional<OrderDetails> findOrder(UUID orderId) {
      return Optional.ofNullable(byId.get(orderId));
    }

    @Override
    public void assignRiderOnOrder(UUID orderId, UUID riderId, Instant now) {}

    @Override
    public void clearRiderOnOrder(UUID orderId, Instant now) {}

    @Override
    public void advanceStatus(
        UUID orderId,
        String fromStatus,
        String toStatus,
        String actorType,
        UUID actorId,
        String notes,
        Instant now) {
      OrderDetails o = byId.get(orderId);
      byId.put(
          orderId,
          new OrderDetails(
              o.orderId(),
              o.orderNumber(),
              toStatus,
              o.riderId(),
              o.pharmacyId(),
              o.pharmacyName(),
              o.pharmacyAddress(),
              o.pharmacyLat(),
              o.pharmacyLng(),
              o.pharmacyPhone(),
              o.zoneId(),
              o.zoneName(),
              o.customerName(),
              o.customerPhone(),
              o.deliveryAddress(),
              o.deliveryLat(),
              o.deliveryLng(),
              o.itemsCount(),
              o.paymentMethod(),
              o.totalPayablePaise(),
              o.estimatedDeliveryAt(),
              o.slaDeadline(),
              o.deliveryOtpHash()));
    }

    @Override
    public Optional<String> peekDeliveryOtp(UUID orderId) {
      return Optional.ofNullable(deliveryOtps.get(orderId));
    }

    @Override
    public boolean verifyDeliveryOtp(UUID orderId, String otp) {
      return otp != null && otp.equals(deliveryOtps.get(orderId));
    }

    @Override
    public String ensureDeliveryOtp(UUID orderId, Instant now) {
      return deliveryOtps.computeIfAbsent(orderId, id -> "3942");
    }
  }

  static final class FakeEarnings implements RiderTripEarningsStore {
    final List<EarningsRecord> rows = new CopyOnWriteArrayList<>();

    @Override
    public void insert(EarningsRecord row) {
      rows.add(row);
    }
  }
}

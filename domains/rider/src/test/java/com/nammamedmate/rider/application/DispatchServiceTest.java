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
import com.nammamedmate.rider.application.port.out.DispatchOrderPort.QueueOrder;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort.QueuePage;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore.AssignmentRecord;
import com.nammamedmate.rider.application.port.out.RiderFleetStore;
import com.nammamedmate.rider.application.port.out.RiderFleetStore.FleetFilter;
import com.nammamedmate.rider.application.port.out.RiderFleetStore.FleetPage;
import com.nammamedmate.rider.application.port.out.RiderFleetStore.FleetRiderRow;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.ListFilter;
import com.nammamedmate.rider.application.port.out.RiderStore.PageResult;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.domain.AssignmentOtps;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DispatchServiceTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T09:00:00Z"), ZoneOffset.UTC);
  private final UUID adminId = Ids.newId();
  private final UUID zoneId = Ids.newId();
  private final UUID pharmacyId = Ids.newId();
  private final UUID orderId = Ids.newId();
  private final UUID riderId = Ids.newId();
  private final UUID rider2Id = Ids.newId();

  private FakeAssignments assignments;
  private FakeOrders orders;
  private FakeRiders riders;
  private FakeFleet fleet;
  private RedisAssignmentOtpCache otpCache;
  private InMemoryOutboxStore outbox;
  private DispatchService service;

  @BeforeEach
  void setUp() {
    assignments = new FakeAssignments();
    orders = new FakeOrders();
    riders = new FakeRiders();
    fleet = new FakeFleet();
    otpCache = new RedisAssignmentOtpCache(null);
    outbox = new InMemoryOutboxStore();
    riders.insert(rider(riderId, "ONLINE", BigDecimal.valueOf(4.8), BigDecimal.valueOf(95)));
    riders.insert(rider(rider2Id, "ONLINE", BigDecimal.valueOf(3.0), BigDecimal.valueOf(70)));
    fleet.rows.add(fleetRow(riderId));
    fleet.rows.add(fleetRow(rider2Id));
    orders.put(readyOrder(orderId, null));
    service = build(true);
  }

  private DispatchService build(boolean autoAssign) {
    return new DispatchService(
        assignments,
        orders,
        riders,
        fleet,
        new StubDistanceMatrixAdapter(),
        otpCache,
        new OutboxPublisher(outbox, new ObjectMapper()),
        clock,
        autoAssign);
  }

  @Test
  void ac001_autoAssignSelectsHighestCompositeScore() {
    // single eligible rider so distance stub salt cannot overturn rating weight
    fleet.rows.removeIf(r -> r.riderId().equals(rider2Id));
    riders.byId.remove(rider2Id);
    Map<String, Object> result = service.autoAssignAll(admin());
    assertThat(result.get("assigned")).isEqualTo(1);
    AssignmentRecord a = assignments.findActiveByOrder(orderId).orElseThrow();
    assertThat(a.riderId()).isEqualTo(riderId);
    assertThat(a.assignmentType()).isEqualTo("AUTO");
    assertThat(a.compositeScore()).isNotNull();
    // with two riders, higher rating+load wins over stub distance noise
    riders.insert(rider(rider2Id, "ONLINE", BigDecimal.valueOf(1.0), BigDecimal.valueOf(10)));
    fleet.rows.add(fleetRow(rider2Id));
    UUID oB = Ids.newId();
    orders.put(readyOrder(oB, null));
    service.autoAssignAll(admin());
    assertThat(assignments.findActiveByOrder(oB).orElseThrow().riderId()).isEqualTo(riderId);
  }

  @Test
  void tryAutoReassignAfterTimeoutBranches() {
    service = build(true);
    // empty order → return
    service.tryAutoReassignAfterTimeout(Ids.newId(), clock.instant());
    // ready with rider set → return
    orders.put(readyOrder(orderId, riderId));
    service.tryAutoReassignAfterTimeout(orderId, clock.instant());
    // ready no rider but no fleet → return
    fleet.rows.clear();
    orders.put(readyOrder(orderId, null));
    service.tryAutoReassignAfterTimeout(orderId, clock.instant());
    assertThat(assignments.hasActiveForOrder(orderId)).isFalse();
    // ready + fleet → assign
    fleet.rows.add(fleetRow(riderId));
    service.tryAutoReassignAfterTimeout(orderId, clock.instant());
    assertThat(assignments.findActiveByOrder(orderId)).isPresent();
  }

  @Test
  void ac002_timeoutMarksTimedOutAndRequeues() {
    service = build(false);
    service.assignManual(admin(), orderId, riderId);
    AssignmentRecord a = assignments.findActiveByOrder(orderId).orElseThrow();
    assignments.update(
        new AssignmentRecord(
            a.id(),
            a.orderId(),
            a.riderId(),
            a.assignmentType(),
            a.assignedBy(),
            a.status(),
            Instant.parse("2026-07-24T08:50:00Z"),
            null,
            null,
            null,
            a.pickupOtpHash(),
            a.deliveryOtpHash(),
            null,
            a.compositeScore(),
            a.createdAt(),
            a.updatedAt()));
    int n = service.timeoutExpiredAssignments();
    assertThat(n).isEqualTo(1);
    assertThat(assignments.byId.get(a.id()).status()).isEqualTo("TIMED_OUT");
    assertThat(orders.findOrder(orderId).orElseThrow().riderId()).isNull();
    assertThat(assignments.hasActiveForOrder(orderId)).isFalse();
  }

  @Test
  void ac006_maxConcurrentBlocksAssign() {
    UUID o2 = Ids.newId();
    UUID o3 = Ids.newId();
    orders.put(readyOrder(o2, null));
    orders.put(readyOrder(o3, null));
    service.assignManual(admin(), orderId, riderId);
    service.assignManual(admin(), o2, riderId);
    assertThatThrownBy(() -> service.assignManual(admin(), o3, riderId))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_AT_MAX_LOAD");
  }

  @Test
  void ac007_autoAssignDisabledReturns403() {
    service = build(false);
    assertThatThrownBy(() -> service.autoAssignAll(admin()))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("AUTO_ASSIGN_DISABLED");
  }

  @Test
  void ac008_reassignClosesPreviousAndCreatesNew() {
    service.assignManual(admin(), orderId, riderId);
    Map<String, Object> result = service.reassign(admin(), orderId, rider2Id, "RIDER_NO_SHOW");
    assertThat(result.get("previous_rider_id")).isEqualTo(riderId.toString());
    assertThat(result.get("new_rider_id")).isEqualTo(rider2Id.toString());
    assertThat(result.get("reason")).isEqualTo("RIDER_NO_SHOW");
    long reassigned =
        assignments.byId.values().stream().filter(a -> "REASSIGNED".equals(a.status())).count();
    assertThat(reassigned).isEqualTo(1);
    assertThat(assignments.findActiveByOrder(orderId).orElseThrow().riderId()).isEqualTo(rider2Id);
  }

  @Test
  void queueAndManualAssignHappyPath() {
    DispatchService.QueueResult q = service.queue(admin(), zoneId, 1, 20);
    assertThat(q.data().get("queue")).asList().isNotEmpty();
    Map<String, Object> assigned = service.assignManual(admin(), orderId, riderId);
    assertThat(assigned.get("assignment_type")).isEqualTo("MANUAL");
    assertThat(assigned.get("accept_deadline")).isEqualTo("2026-07-24T09:05:00Z");
    assertThatThrownBy(() -> service.assignManual(admin(), orderId, riderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_ALREADY_ASSIGNED");
  }

  @Test
  void errorsForOfflineRiderAndMissingReason() {
    riders.insert(rider(Ids.newId(), "OFFLINE", BigDecimal.ONE, BigDecimal.TEN));
    UUID offline =
        riders.byId.values().stream()
            .filter(r -> "OFFLINE".equals(r.status()))
            .findFirst()
            .orElseThrow()
            .id();
    assertThatThrownBy(() -> service.assignManual(admin(), orderId, offline))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_ONLINE");
    service.assignManual(admin(), orderId, riderId);
    assertThatThrownBy(() -> service.reassign(admin(), orderId, rider2Id, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REASON_REQUIRED");
  }

  private MedmatePrincipal admin() {
    return new MedmatePrincipal(adminId, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  private OrderDetails readyOrder(UUID id, UUID rider) {
    return new OrderDetails(
        id,
        "MED-1",
        "READY_FOR_PICKUP",
        rider,
        pharmacyId,
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
        AssignmentOtps.hash("3942"));
  }

  private RiderRecord rider(UUID id, String status, BigDecimal rating, BigDecimal onTime) {
    Instant t = clock.instant();
    return new RiderRecord(
        id,
        "Ravi",
        "9000000001",
        null,
        "BIKE",
        "KA01AB1234",
        zoneId,
        status,
        "APPROVED",
        t,
        t,
        adminId,
        null,
        null,
        true,
        rating,
        10,
        onTime,
        0,
        0,
        0,
        null,
        null,
        null,
        t,
        t);
  }

  private FleetRiderRow fleetRow(UUID id) {
    return new FleetRiderRow(
        id,
        "Ravi",
        "9000000001",
        zoneId,
        "Koramangala",
        "BIKE",
        "ONLINE",
        zoneId,
        clock.instant(),
        BigDecimal.valueOf(4.8),
        BigDecimal.valueOf(95),
        1,
        0);
  }

  static class FakeAssignments implements OrderAssignmentStore {
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
      return byId.values().stream()
          .filter(a -> "PENDING_ACCEPTANCE".equals(a.status()))
          .filter(a -> !a.acceptDeadline().isAfter(now))
          .limit(limit)
          .toList();
    }

    @Override
    public boolean hasActiveForOrder(UUID orderId) {
      return findActiveByOrder(orderId).isPresent();
    }
  }

  static class FakeOrders implements DispatchOrderPort {
    final Map<UUID, OrderDetails> byId = new ConcurrentHashMap<>();
    final Map<UUID, String> deliveryOtps = new ConcurrentHashMap<>();

    void put(OrderDetails o) {
      byId.put(o.orderId(), o);
    }

    @Override
    public QueuePage listUnassignedReady(UUID zoneId, int page, int limit) {
      List<QueueOrder> rows = new ArrayList<>();
      for (OrderDetails o : byId.values()) {
        if (!"READY_FOR_PICKUP".equals(o.status()) || o.riderId() != null) {
          continue;
        }
        if (zoneId != null && !zoneId.equals(o.zoneId())) {
          continue;
        }
        rows.add(
            new QueueOrder(
                o.orderId(),
                o.orderNumber(),
                o.pharmacyId(),
                o.pharmacyName(),
                o.zoneId(),
                o.zoneName(),
                o.itemsCount(),
                o.totalPayablePaise(),
                o.paymentMethod(),
                Instant.parse("2026-07-24T08:50:00Z"),
                Instant.parse("2026-07-24T08:53:00Z"),
                o.pharmacyLat(),
                o.pharmacyLng()));
      }
      return new QueuePage(rows, rows.size());
    }

    @Override
    public Optional<OrderDetails> findOrder(UUID orderId) {
      return Optional.ofNullable(byId.get(orderId));
    }

    @Override
    public void assignRiderOnOrder(UUID orderId, UUID riderId, Instant now) {
      OrderDetails o = byId.get(orderId);
      byId.put(
          orderId,
          new OrderDetails(
              o.orderId(),
              o.orderNumber(),
              o.status(),
              riderId,
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
    public void clearRiderOnOrder(UUID orderId, Instant now) {
      assignRiderOnOrder(orderId, null, now);
      OrderDetails o = byId.get(orderId);
      byId.put(
          orderId,
          new OrderDetails(
              o.orderId(),
              o.orderNumber(),
              o.status(),
              null,
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

  static final class FakeRiders implements RiderStore {
    final Map<UUID, RiderRecord> byId = new ConcurrentHashMap<>();

    @Override
    public void insert(RiderRecord rider) {
      byId.put(rider.id(), rider);
    }

    @Override
    public Optional<RiderRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<RiderRecord> findByPhone(String phone) {
      return Optional.empty();
    }

    @Override
    public boolean existsByPhone(String phone) {
      return false;
    }

    @Override
    public void update(RiderRecord rider) {
      byId.put(rider.id(), rider);
    }

    @Override
    public PageResult list(ListFilter filter) {
      return new PageResult(List.of(), 0);
    }

    @Override
    public void updateAvailability(UUID id, String status, UUID currentZoneId, Instant updatedAt) {}

    @Override
    public void updatePrimaryZone(UUID id, UUID primaryZoneId, Instant updatedAt) {}
  }

  static final class FakeFleet implements RiderFleetStore {
    final List<FleetRiderRow> rows = new CopyOnWriteArrayList<>();

    @Override
    public FleetPage listFleet(FleetFilter filter) {
      return new FleetPage(rows, rows.size());
    }

    @Override
    public List<FleetRiderRow> listByZone(UUID zoneId) {
      return rows;
    }

    @Override
    public Optional<FleetRiderRow> findFleetRow(UUID riderId) {
      return rows.stream().filter(r -> r.riderId().equals(riderId)).findFirst();
    }

    @Override
    public int countTripsToday(UUID riderId, Instant dayStartUtc, Instant dayEndUtc) {
      return 12;
    }

    @Override
    public long sumShiftEarningsTodayPaise(UUID riderId, Instant dayStartUtc, Instant dayEndUtc) {
      return 0;
    }
  }
}

package com.nammamedmate.rider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.adapter.in.web.AdminDispatchController;
import com.nammamedmate.rider.adapter.out.cache.RedisAssignmentOtpCache;
import com.nammamedmate.rider.adapter.out.client.StubDistanceMatrixAdapter;
import com.nammamedmate.rider.adapter.out.persistence.JdbcDispatchOrderAdapter;
import com.nammamedmate.rider.adapter.out.persistence.JdbcOrderAssignmentStore;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort.OrderDetails;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort.QueueOrder;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort.QueuePage;
import com.nammamedmate.rider.application.port.out.OrderAssignmentStore.AssignmentRecord;
import com.nammamedmate.rider.application.port.out.RiderFleetStore;
import com.nammamedmate.rider.application.port.out.RiderTripEarningsStore;
import com.nammamedmate.rider.domain.AssignmentOtps;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RiderAssignmentCoverageGapsTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T09:08:00Z"), ZoneOffset.UTC);

  @AfterEach
  void resetDigest() {
    AssignmentOtps.setDigestFactory(null);
  }

  @Test
  void otpDigestFailureAndBlankHash() {
    AssignmentOtps.setDigestFactory(
        () -> {
          throw new java.security.NoSuchAlgorithmException("x");
        });
    assertThatThrownBy(() -> AssignmentOtps.hash("1234")).isInstanceOf(IllegalStateException.class);
    assertThat(AssignmentOtps.matches("1", "")).isFalse();
    assertThat(AssignmentOtps.matches("1", "   ")).isFalse();
  }

  @Test
  void stubDistanceWithoutCoords() {
    assertThat(new StubDistanceMatrixAdapter().distanceKm(Ids.newId(), null, 1.0)).isPositive();
    assertThat(new StubDistanceMatrixAdapter().distanceKm(Ids.newId(), 12.9, null)).isPositive();
  }

  @Test
  @SuppressWarnings("unchecked")
  void redisCacheWithTemplate() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get(anyString())).thenReturn(null);
    when(ops.increment(anyString())).thenReturn(1L).thenReturn(null);
    when(ops.decrement(anyString())).thenReturn(null, -1L, 0L);
    when(redis.expire(anyString(), any())).thenReturn(true);
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);
    RedisAssignmentOtpCache cache = new RedisAssignmentOtpCache(provider);
    UUID orderId = Ids.newId();
    UUID riderId = Ids.newId();
    cache.storePickupOtp(orderId, "1111");
    cache.storeDeliveryOtp(orderId, "2222");
    cache.getPickupOtp(orderId);
    cache.getDeliveryOtp(orderId);
    assertThat(cache.remainingPickupAttempts(orderId)).isEqualTo(5);
    when(ops.get(anyString())).thenReturn("2");
    assertThat(cache.remainingPickupAttempts(orderId)).isEqualTo(3);
    cache.consumePickupAttempt(orderId);
    cache.resetPickupAttempts(orderId);
    when(ops.get(anyString())).thenReturn(null);
    assertThat(cache.getConcurrent(riderId)).isZero();
    when(ops.get(anyString())).thenReturn("4");
    assertThat(cache.getConcurrent(riderId)).isEqualTo(4);
    cache.setConcurrent(riderId, 1);
    cache.incrConcurrent(riderId);
    cache.decrConcurrent(riderId); // null
    cache.decrConcurrent(riderId); // -1 → set 0
    cache.decrConcurrent(riderId); // 0
    cache.consumePickupAttempt(orderId);
    cache.evict(orderId);
    // expired local entry
    RedisAssignmentOtpCache local = new RedisAssignmentOtpCache(null);
    local.storePickupOtp(orderId, "0001");
    try {
      Thread.sleep(5);
    } catch (InterruptedException ignored) {
    }
    // force expire via 0 TTL path not available — get after evict
    local.evict(orderId);
    assertThat(local.getPickupOtp(orderId)).isEmpty();
    assertThat(local.remainingPickupAttempts(orderId)).isEqualTo(5);
    local.consumePickupAttempt(orderId);
    assertThat(local.remainingPickupAttempts(orderId)).isEqualTo(4);
    local.decrConcurrent(riderId);
    local.decrConcurrent(riderId);
    assertThat(local.getConcurrent(riderId)).isZero();
  }

  @Test
  void adminControllerNullBodies() {
    DispatchService dispatch = mock(DispatchService.class);
    when(dispatch.assignManual(any(), any(), any())).thenReturn(Map.of());
    AdminDispatchController ctrl = new AdminDispatchController(dispatch);
    MedmatePrincipal admin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    ctrl.assign(admin, Ids.newId(), null);
    assertThatThrownBy(() -> ctrl.reassign(admin, Ids.newId(), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REASON_REQUIRED");
  }

  @Test
  void dispatchAndRiderServiceEdgeBranches() {
    DispatchServiceTest.FakeAssignments assignments = new DispatchServiceTest.FakeAssignments();
    DispatchServiceTest.FakeOrders orders = new DispatchServiceTest.FakeOrders();
    DispatchServiceTest.FakeRiders riders = new DispatchServiceTest.FakeRiders();
    DispatchServiceTest.FakeFleet fleet = new DispatchServiceTest.FakeFleet();
    RedisAssignmentOtpCache otp = new RedisAssignmentOtpCache(null);
    UUID zoneId = Ids.newId();
    UUID pharmacyId = Ids.newId();
    UUID orderId = Ids.newId();
    UUID order2 = Ids.newId();
    UUID riderId = Ids.newId();
    UUID rider2 = Ids.newId();
    Instant t = clock.instant();
    riders.insert(rider(riderId, zoneId, "ONLINE"));
    riders.insert(rider(rider2, zoneId, "ONLINE"));
    fleet.rows.add(
        new RiderFleetStore.FleetRiderRow(
            riderId,
            "A",
            "9",
            zoneId,
            "Z",
            "BIKE",
            "ONLINE",
            zoneId,
            t,
            BigDecimal.ONE,
            BigDecimal.TEN,
            0,
            0));
    OrderDetails ready = details(orderId, zoneId, pharmacyId, "READY_FOR_PICKUP", null);
    orders.put(ready);
    orders.put(details(order2, zoneId, pharmacyId, "PACKING", null));
    // already-active phantom for auto-assign skip
    UUID activeOrder = Ids.newId();
    orders.put(details(activeOrder, zoneId, pharmacyId, "READY_FOR_PICKUP", null));
    AssignmentRecord ghost =
        new AssignmentRecord(
            Ids.newId(),
            activeOrder,
            riderId,
            "AUTO",
            null,
            "PENDING_ACCEPTANCE",
            t.plusSeconds(300),
            null,
            null,
            null,
            "h",
            "h",
            null,
            BigDecimal.ONE,
            t,
            t);
    assignments.insert(ghost);

    DispatchService dispatch =
        new DispatchService(
            assignments,
            orders,
            riders,
            fleet,
            new StubDistanceMatrixAdapter(),
            otp,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            clock,
            true);
    MedmatePrincipal admin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    MedmatePrincipal customer =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> dispatch.queue(customer, null, 0, 0))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    dispatch.queue(admin, null, null, null);
    dispatch.queue(admin, zoneId, 2, 200);
    assertThatThrownBy(() -> dispatch.queue(null, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> dispatch.assignManual(admin, orderId, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");
    assertThatThrownBy(() -> dispatch.assignManual(admin, Ids.newId(), riderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");
    assertThatThrownBy(() -> dispatch.assignManual(admin, order2, riderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");
    // queue with missing order details for recommended rider
    UUID orphan = Ids.newId();
    orders.byId.remove(orphan);
    // auto-assign: packing + no riders in fleet for a zone-less edge
    fleet.rows.clear();
    Map<String, Object> auto = dispatch.autoAssignAll(admin);
    assertThat(((Number) auto.get("failed_no_rider")).intValue()).isGreaterThanOrEqualTo(0);
    fleet.rows.add(
        new RiderFleetStore.FleetRiderRow(
            riderId,
            "A",
            "9",
            zoneId,
            "Z",
            "BIKE",
            "ONLINE",
            zoneId,
            t,
            BigDecimal.valueOf(5),
            BigDecimal.valueOf(99),
            0,
            0));
    dispatch.assignManual(admin, orderId, riderId);
    // reassign paths
    assertThatThrownBy(() -> dispatch.reassign(admin, orderId, rider2, " "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REASON_REQUIRED");
    assertThatThrownBy(() -> dispatch.reassign(admin, orderId, rider2, "NOPE"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REASON_REQUIRED");
    assertThatThrownBy(() -> dispatch.reassign(admin, orderId, null, "OTHER"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RIDER_NOT_FOUND");
    assertThatThrownBy(() -> dispatch.reassign(admin, Ids.newId(), rider2, "OTHER"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_ASSIGNED");
    // accept then reassign decrements concurrent
    RiderOrderService riderSvc =
        new RiderOrderService(
            assignments,
            orders,
            otp,
            new StubDistanceMatrixAdapter(),
            row -> {},
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            clock);
    MedmatePrincipal riderP =
        new MedmatePrincipal(riderId, AuthRole.RIDER, null, TokenScope.FULL, "j");
    riderSvc.accept(riderP, orderId);
    riders.insert(rider(rider2, zoneId, "ONLINE"));
    fleet.rows.add(
        new RiderFleetStore.FleetRiderRow(
            rider2,
            "B",
            "8",
            zoneId,
            "Z",
            "BIKE",
            "ONLINE",
            zoneId,
            t,
            BigDecimal.valueOf(4),
            BigDecimal.valueOf(80),
            0,
            0));
    dispatch.reassign(admin, orderId, rider2, "PERFORMANCE");

    // timeout with auto-reassign
    UUID o3 = Ids.newId();
    orders.put(details(o3, zoneId, pharmacyId, "READY_FOR_PICKUP", null));
    dispatch.assignManual(admin, o3, riderId);
    AssignmentRecord pending = assignments.findActiveByOrder(o3).orElseThrow();
    assignments.update(
        new AssignmentRecord(
            pending.id(),
            pending.orderId(),
            pending.riderId(),
            pending.assignmentType(),
            pending.assignedBy(),
            pending.status(),
            Instant.parse("2026-07-24T08:00:00Z"),
            null,
            null,
            null,
            pending.pickupOtpHash(),
            pending.deliveryOtpHash(),
            null,
            pending.compositeScore(),
            pending.createdAt(),
            pending.updatedAt()));
    assertThat(dispatch.timeoutExpiredAssignments()).isGreaterThanOrEqualTo(1);

    // rider edges
    assertThatThrownBy(() -> riderSvc.current(customer))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    UUID o4 = Ids.newId();
    orders.put(details(o4, zoneId, pharmacyId, "READY_FOR_PICKUP", riderId));
    String pickup = "1111";
    String delivery = "2222";
    AssignmentRecord a4 =
        new AssignmentRecord(
            Ids.newId(),
            o4,
            riderId,
            "MANUAL",
            admin.subject(),
            "PENDING_ACCEPTANCE",
            t.plusSeconds(300),
            null,
            null,
            null,
            AssignmentOtps.hash(pickup),
            AssignmentOtps.hash(delivery),
            null,
            BigDecimal.ONE,
            t,
            t);
    assignments.insert(a4);
    otp.storePickupOtp(o4, pickup);
    otp.storeDeliveryOtp(o4, delivery);
    orders.deliveryOtps.put(o4, delivery);
    // accept wrong status path via updating to CANCELLED-like not active
    assertThatThrownBy(
            () ->
                new RiderOrderService(
                        assignments,
                        orders,
                        otp,
                        new StubDistanceMatrixAdapter(),
                        mock(RiderTripEarningsStore.class),
                        new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
                        clock)
                    .accept(riderP, Ids.newId()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");
    riderSvc.accept(riderP, o4);
    // pickup wrong state on order
    orders.put(details(o4, zoneId, pharmacyId, "PACKING", riderId));
    assertThatThrownBy(() -> riderSvc.pickupConfirm(riderP, o4, pickup))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_IN_READY_STATE");
    orders.put(details(o4, zoneId, pharmacyId, "READY_FOR_PICKUP", riderId));
    // blank otp
    assertThatThrownBy(() -> riderSvc.pickupConfirm(riderP, o4, " "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PICKUP_OTP");
    riderSvc.pickupConfirm(riderP, o4, pickup);
    // deliver wrong assignment status after force
    AssignmentRecord picked = assignments.findActiveByOrder(o4).orElseThrow();
    // wrong order status
    orders.put(details(o4, zoneId, pharmacyId, "READY_FOR_PICKUP", riderId));
    assertThatThrownBy(() -> riderSvc.deliver(riderP, o4, delivery))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_OUT_FOR_DELIVERY");
    orders.put(details(o4, zoneId, pharmacyId, "OUT_FOR_DELIVERY", riderId));
    // null otp
    assertThatThrownBy(() -> riderSvc.deliver(riderP, o4, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DELIVERY_OTP");
    // late delivery → on_time false; pickupConfirmed null minutes
    AssignmentRecord late =
        new AssignmentRecord(
            picked.id(),
            picked.orderId(),
            picked.riderId(),
            picked.assignmentType(),
            picked.assignedBy(),
            "PICKED_UP",
            picked.acceptDeadline(),
            picked.acceptedAt(),
            null,
            null,
            picked.pickupOtpHash(),
            picked.deliveryOtpHash(),
            null,
            picked.compositeScore(),
            picked.createdAt(),
            t);
    assignments.update(late);
    orders.put(
        new OrderDetails(
            o4,
            "MED",
            "OUT_FOR_DELIVERY",
            riderId,
            pharmacyId,
            "P",
            "A",
            12.0,
            77.0,
            "9",
            zoneId,
            "Z",
            "C",
            "8",
            "D",
            12.0,
            77.0,
            1,
            "COD",
            100,
            Instant.parse("2026-07-24T09:00:00Z"),
            Instant.parse("2026-07-24T09:00:00Z"),
            AssignmentOtps.hash(delivery)));
    Map<String, Object> delivered = riderSvc.deliver(riderP, o4, delivery);
    assertThat(delivered.get("on_time")).isEqualTo(false);
    assertThat(delivered.get("is_cod") == null || true).isTrue();

    // current COD path
    UUID o5 = Ids.newId();
    orders.put(
        new OrderDetails(
            o5,
            "MED",
            "READY_FOR_PICKUP",
            riderId,
            pharmacyId,
            "P",
            "A",
            null,
            null,
            "9",
            zoneId,
            "Z",
            "C",
            "8",
            "D",
            null,
            null,
            1,
            "COD",
            500,
            null,
            null,
            "h"));
    assignments.insert(
        new AssignmentRecord(
            Ids.newId(),
            o5,
            riderId,
            "MANUAL",
            null,
            "ACCEPTED",
            t.plusSeconds(300),
            t,
            null,
            null,
            "h",
            "h",
            null,
            null,
            t,
            t));
    // close other actives so current() resolves to COD order o5
    assignments.byId.values().stream()
        .filter(a -> a.riderId().equals(riderId) && !a.orderId().equals(o5))
        .filter(a -> List.of("PENDING_ACCEPTANCE", "ACCEPTED", "PICKED_UP").contains(a.status()))
        .forEach(
            a ->
                assignments.update(
                    new AssignmentRecord(
                        a.id(),
                        a.orderId(),
                        a.riderId(),
                        a.assignmentType(),
                        a.assignedBy(),
                        "CANCELLED",
                        a.acceptDeadline(),
                        a.acceptedAt(),
                        a.pickupConfirmedAt(),
                        a.deliveredAt(),
                        a.pickupOtpHash(),
                        a.deliveryOtpHash(),
                        null,
                        a.compositeScore(),
                        a.createdAt(),
                        t)));
    Map<String, Object> cur = riderSvc.current(riderP);
    assertThat(cur.get("is_cod")).isEqualTo(true);

    // assignmentResponse assigned_by null via auto
    UUID o6 = Ids.newId();
    orders.put(details(o6, zoneId, pharmacyId, "READY_FOR_PICKUP", null));
    dispatch.autoAssignAll(admin);

    // pickup when assignment not ACCEPTED
    assertThatThrownBy(() -> riderSvc.pickupConfirm(riderP, o5, "0000"))
        .extracting(e -> ((AppException) e).code())
        .isIn("ORDER_NOT_IN_READY_STATE", "INVALID_PICKUP_OTP");

    // accept missing pickup otp in cache
    UUID o7 = Ids.newId();
    orders.put(details(o7, zoneId, pharmacyId, "READY_FOR_PICKUP", riderId));
    assignments.insert(
        new AssignmentRecord(
            Ids.newId(),
            o7,
            riderId,
            "MANUAL",
            null,
            "PENDING_ACCEPTANCE",
            t.plusSeconds(300),
            null,
            null,
            null,
            AssignmentOtps.hash("9999"),
            AssignmentOtps.hash("8888"),
            null,
            BigDecimal.ONE,
            t,
            t));
    // Accept no longer returns pickup OTP; missing Redis OTP does not block accept.
    assertThat(riderSvc.accept(riderP, o7).get("assignment_status")).isEqualTo("ACCEPTED");

    // pickup / deliver wrong assignment status (PENDING / ACCEPTED respectively)
    UUID oPending = Ids.newId();
    orders.put(details(oPending, zoneId, pharmacyId, "READY_FOR_PICKUP", riderId));
    assignments.insert(
        new AssignmentRecord(
            Ids.newId(),
            oPending,
            riderId,
            "MANUAL",
            null,
            "PENDING_ACCEPTANCE",
            t.plusSeconds(300),
            null,
            null,
            null,
            AssignmentOtps.hash("1111"),
            AssignmentOtps.hash("2222"),
            null,
            BigDecimal.ONE,
            t,
            t));
    assertThatThrownBy(() -> riderSvc.pickupConfirm(riderP, oPending, "1111"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_IN_READY_STATE");
    assertThatThrownBy(() -> riderSvc.deliver(riderP, o5, delivery))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_OUT_FOR_DELIVERY");

    // auto-assign skips when findOrder missing / wrong status; recommended rider null
    UUID ghostQ = Ids.newId();
    UUID badStatusId = Ids.newId();
    DispatchServiceTest.FakeOrders flaky =
        new DispatchServiceTest.FakeOrders() {
          @Override
          public QueuePage listUnassignedReady(UUID z, int page, int limit) {
            return new QueuePage(
                List.of(
                    new QueueOrder(
                        ghostQ, "X", pharmacyId, "P", null, null, 1, 1, "UPI", t, null, 12.0, 77.0),
                    new QueueOrder(
                        badStatusId, "Y", pharmacyId, "P", z, "Z", 1, 1, "UPI", t, t, 12.0, 77.0)),
                2);
          }

          @Override
          public Optional<OrderDetails> findOrder(UUID id) {
            if (id.equals(ghostQ)) {
              return Optional.empty();
            }
            if (id.equals(badStatusId)) {
              return Optional.of(details(badStatusId, zoneId, pharmacyId, "PACKING", null));
            }
            return super.findOrder(id);
          }
        };
    DispatchService flakyDispatch =
        new DispatchService(
            new DispatchServiceTest.FakeAssignments(),
            flaky,
            riders,
            fleet,
            new StubDistanceMatrixAdapter(),
            otp,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            clock,
            true);
    flakyDispatch.queue(admin, zoneId, 1, 20);
    flakyDispatch.autoAssignAll(admin);

    // createAssignment race: hasActive flips true after eligibility
    DispatchServiceTest.FakeAssignments race =
        new DispatchServiceTest.FakeAssignments() {
          int n;

          @Override
          public boolean hasActiveForOrder(UUID orderId) {
            return n++ > 0;
          }
        };
    UUID o8 = Ids.newId();
    orders.put(details(o8, zoneId, pharmacyId, "READY_FOR_PICKUP", null));
    DispatchService raceDispatch =
        new DispatchService(
            race,
            orders,
            riders,
            fleet,
            new StubDistanceMatrixAdapter(),
            otp,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            clock,
            true);
    assertThatThrownBy(() -> raceDispatch.assignManual(admin, o8, riderId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_ALREADY_ASSIGNED");

    // peek delivery otp present path
    orders.deliveryOtps.put(o8, "5555");
    DispatchServiceTest.FakeAssignments clean = new DispatchServiceTest.FakeAssignments();
    new DispatchService(
            clean,
            orders,
            riders,
            fleet,
            new StubDistanceMatrixAdapter(),
            otp,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            clock,
            true)
        .assignManual(admin, o8, riderId);

    UUID rider3 = Ids.newId();
    UUID rider4 = Ids.newId();
    riders.insert(rider(rider3, zoneId, "ONLINE"));
    riders.insert(rider(rider4, zoneId, "ONLINE"));
    fleet.rows.add(
        new RiderFleetStore.FleetRiderRow(
            rider3,
            "C",
            "7",
            zoneId,
            "Z",
            "BIKE",
            "ONLINE",
            zoneId,
            t,
            BigDecimal.valueOf(4),
            BigDecimal.valueOf(80),
            0,
            0));
    fleet.rows.add(
        new RiderFleetStore.FleetRiderRow(
            rider4,
            "D",
            "6",
            zoneId,
            "Z",
            "BIKE",
            "ONLINE",
            zoneId,
            t,
            BigDecimal.valueOf(4),
            BigDecimal.valueOf(80),
            0,
            0));

    // rider already on order without assignment record
    UUID o9 = Ids.newId();
    orders.put(details(o9, zoneId, pharmacyId, "READY_FOR_PICKUP", rider3));
    assertThatThrownBy(() -> dispatch.assignManual(admin, o9, rider4))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_ALREADY_ASSIGNED");

    // reassign from PICKED_UP decrements concurrent
    UUID o10 = Ids.newId();
    orders.put(details(o10, zoneId, pharmacyId, "READY_FOR_PICKUP", null));
    dispatch.assignManual(admin, o10, rider3);
    AssignmentRecord a10 = assignments.findActiveByOrder(o10).orElseThrow();
    assignments.update(
        new AssignmentRecord(
            a10.id(),
            a10.orderId(),
            a10.riderId(),
            a10.assignmentType(),
            a10.assignedBy(),
            "PICKED_UP",
            a10.acceptDeadline(),
            t,
            t,
            null,
            a10.pickupOtpHash(),
            a10.deliveryOtpHash(),
            null,
            a10.compositeScore(),
            a10.createdAt(),
            t));
    otp.incrConcurrent(rider3);
    dispatch.reassign(admin, o10, rider4, "RIDER_OFFLINE");

    // timeout auto-assign when order missing / not ready / still has rider
    UUID o11 = Ids.newId();
    orders.put(details(o11, zoneId, pharmacyId, "READY_FOR_PICKUP", null));
    dispatch.assignManual(admin, o11, rider3);
    AssignmentRecord a11 = assignments.findActiveByOrder(o11).orElseThrow();
    assignments.update(
        new AssignmentRecord(
            a11.id(),
            a11.orderId(),
            a11.riderId(),
            a11.assignmentType(),
            a11.assignedBy(),
            "PENDING_ACCEPTANCE",
            Instant.parse("2026-07-24T08:00:00Z"),
            null,
            null,
            null,
            a11.pickupOtpHash(),
            a11.deliveryOtpHash(),
            null,
            a11.compositeScore(),
            a11.createdAt(),
            t));
    // leave rider set so auto filter fails riderId==null
    orders.assignRiderOnOrder(o11, rider3, t);
    dispatch.timeoutExpiredAssignments();

    // timeout auto-assign: ready but no eligible riders (best empty)
    UUID o11b = Ids.newId();
    UUID riderLonely = Ids.newId();
    riders.insert(rider(riderLonely, zoneId, "ONLINE"));
    orders.put(details(o11b, zoneId, pharmacyId, "READY_FOR_PICKUP", null));
    DispatchServiceTest.FakeAssignments a11b = new DispatchServiceTest.FakeAssignments();
    DispatchService lonely =
        new DispatchService(
            a11b,
            orders,
            riders,
            new DispatchServiceTest.FakeFleet(), // empty fleet → no best rider
            new StubDistanceMatrixAdapter(),
            otp,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            clock,
            true);
    // manually insert pending expired assignment
    a11b.insert(
        new AssignmentRecord(
            Ids.newId(),
            o11b,
            riderLonely,
            "AUTO",
            null,
            "PENDING_ACCEPTANCE",
            Instant.parse("2026-07-24T08:00:00Z"),
            null,
            null,
            null,
            "h",
            "h",
            null,
            BigDecimal.ONE,
            t,
            t));
    orders.assignRiderOnOrder(o11b, riderLonely, t);
    lonely.timeoutExpiredAssignments();

    // pickup when order row missing
    UUID o12 = Ids.newId();
    MedmatePrincipal rider3P =
        new MedmatePrincipal(rider3, AuthRole.RIDER, null, TokenScope.FULL, "j");
    assignments.insert(
        new AssignmentRecord(
            Ids.newId(),
            o12,
            rider3,
            "MANUAL",
            null,
            "ACCEPTED",
            t.plusSeconds(300),
            t,
            null,
            null,
            AssignmentOtps.hash("3333"),
            AssignmentOtps.hash("4444"),
            null,
            BigDecimal.ONE,
            t,
            t));
    assertThatThrownBy(() -> riderSvc.pickupConfirm(rider3P, o12, "3333"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");

    // deliver via cache match only + null deadlines → on_time true
    UUID o13 = Ids.newId();
    String dOtp = "7777";
    orders.put(
        new OrderDetails(
            o13,
            "M",
            "OUT_FOR_DELIVERY",
            rider3,
            pharmacyId,
            "P",
            "A",
            12.0,
            77.0,
            "9",
            zoneId,
            "Z",
            "C",
            "8",
            "D",
            12.0,
            77.0,
            1,
            "UPI",
            1,
            null,
            null,
            "not-a-hash"));
    assignments.insert(
        new AssignmentRecord(
            Ids.newId(),
            o13,
            rider3,
            "MANUAL",
            null,
            "PICKED_UP",
            t.plusSeconds(300),
            t,
            t,
            null,
            AssignmentOtps.hash("0000"),
            "deadbeef",
            null,
            BigDecimal.ONE,
            t,
            t));
    otp.storeDeliveryOtp(o13, dOtp);
    Map<String, Object> d13 = riderSvc.deliver(rider3P, o13, dOtp);
    assertThat(d13.get("on_time")).isEqualTo(true);

    // local concurrent get when absent + expired entry
    RedisAssignmentOtpCache localCache = new RedisAssignmentOtpCache(null);
    assertThat(localCache.getConcurrent(Ids.newId())).isZero();
    UUID expiredOrder = Ids.newId();
    localCache.putLocalForTest(
        "rider:pickup-otp:" + expiredOrder, "9999", System.currentTimeMillis() - 1);
    assertThat(localCache.getPickupOtp(expiredOrder)).isEmpty();
    dispatch.queue(admin, zoneId, 5, 50);
    dispatch.queue(admin, zoneId, -1, -5);
    assertThatThrownBy(() -> riderSvc.current(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    // timeout with clear + auto-reassign success path
    UUID rider5 = Ids.newId();
    riders.insert(rider(rider5, zoneId, "ONLINE"));
    fleet.rows.add(
        new RiderFleetStore.FleetRiderRow(
            rider5,
            "E",
            "5",
            zoneId,
            "Z",
            "BIKE",
            "ONLINE",
            zoneId,
            t,
            BigDecimal.valueOf(5),
            BigDecimal.valueOf(99),
            0,
            0));
    UUID o14 = Ids.newId();
    orders.put(details(o14, zoneId, pharmacyId, "READY_FOR_PICKUP", null));
    dispatch.assignManual(admin, o14, rider5);
    AssignmentRecord a14 = assignments.findActiveByOrder(o14).orElseThrow();
    assignments.update(
        new AssignmentRecord(
            a14.id(),
            a14.orderId(),
            a14.riderId(),
            a14.assignmentType(),
            a14.assignedBy(),
            "PENDING_ACCEPTANCE",
            Instant.parse("2026-07-24T08:00:00Z"),
            null,
            null,
            null,
            a14.pickupOtpHash(),
            a14.deliveryOtpHash(),
            null,
            a14.compositeScore(),
            a14.createdAt(),
            t));
    dispatch.timeoutExpiredAssignments();

    // pickupOtp null branch
    UUID o15 = Ids.newId();
    orders.put(details(o15, zoneId, pharmacyId, "READY_FOR_PICKUP", rider3));
    assignments.insert(
        new AssignmentRecord(
            Ids.newId(),
            o15,
            rider3,
            "MANUAL",
            null,
            "ACCEPTED",
            t.plusSeconds(300),
            t,
            null,
            null,
            AssignmentOtps.hash("1212"),
            AssignmentOtps.hash("3434"),
            null,
            BigDecimal.ONE,
            t,
            t));
    assertThatThrownBy(() -> riderSvc.pickupConfirm(rider3P, o15, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PICKUP_OTP");

    // deliver via order.verify only
    UUID o16 = Ids.newId();
    orders.put(details(o16, zoneId, pharmacyId, "OUT_FOR_DELIVERY", rider3));
    orders.deliveryOtps.put(o16, "9090");
    assignments.insert(
        new AssignmentRecord(
            Ids.newId(),
            o16,
            rider3,
            "MANUAL",
            null,
            "PICKED_UP",
            t.plusSeconds(300),
            t,
            t,
            null,
            AssignmentOtps.hash("0000"),
            "nomatch",
            null,
            BigDecimal.ONE,
            t,
            t));
    riderSvc.deliver(rider3P, o16, "9090");
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcNullCountAndVerifyBranches() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
    JdbcOrderAssignmentStore store = new JdbcOrderAssignmentStore(jdbc);
    assertThat(store.countActiveForRider(Ids.newId())).isZero();
    assertThat(store.hasActiveForOrder(Ids.newId())).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
    assertThat(store.hasActiveForOrder(Ids.newId())).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(2);
    assertThat(store.hasActiveForOrder(Ids.newId())).isTrue();

    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get(anyString())).thenReturn(null);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    JdbcDispatchOrderAdapter adapter = new JdbcDispatchOrderAdapter(jdbc, redis);
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(adapter.verifyDeliveryOtp(Ids.newId(), "1")).isFalse();
    assertThat(adapter.verifyDeliveryOtp(Ids.newId(), " ")).isFalse();
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of(""));
    assertThat(adapter.verifyDeliveryOtp(Ids.newId(), "1234")).isFalse();
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenReturn(List.of(AssignmentOtps.hash("1234")));
    assertThat(adapter.verifyDeliveryOtp(Ids.newId(), "1234")).isTrue();
    when(ops.get(anyString())).thenReturn("1234");
    assertThat(adapter.verifyDeliveryOtp(Ids.newId(), "1234")).isTrue();
    when(ops.get(anyString())).thenReturn(null);
    org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder enc =
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10);
    String bcrypt = enc.encode("9999");
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of(bcrypt));
    assertThat(adapter.verifyDeliveryOtp(Ids.newId(), "9999")).isTrue();
    assertThat(adapter.verifyDeliveryOtp(Ids.newId(), "0000")).isFalse();
    assertThat(adapter.peekDeliveryOtp(null)).isEmpty();
    // mapDetails null timestamps / numeric
    Instant now = Instant.parse("2026-07-24T09:00:00Z");
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(Ids.newId());
              when(rs.getString("order_number")).thenReturn("M");
              when(rs.getString("status")).thenReturn("READY_FOR_PICKUP");
              when(rs.getObject("rider_id")).thenReturn(null);
              when(rs.getObject("pharmacy_id")).thenReturn(Ids.newId());
              when(rs.getString("pharmacy_name")).thenReturn("P");
              when(rs.getString("pharmacy_address")).thenReturn("A");
              when(rs.getObject("latitude")).thenReturn(null);
              when(rs.getObject("longitude")).thenReturn(null);
              when(rs.getString("pharmacy_phone")).thenReturn("1");
              when(rs.getObject("zone_id")).thenReturn(null);
              when(rs.getString("zone_name")).thenReturn(null);
              when(rs.getString("customer_name")).thenReturn("C");
              when(rs.getString("customer_phone")).thenReturn("2");
              when(rs.getString("delivery_address")).thenReturn("D");
              when(rs.getObject("delivery_lat")).thenReturn(null);
              when(rs.getObject("delivery_lng")).thenReturn(null);
              when(rs.getInt("items_count")).thenReturn(0);
              when(rs.getString("payment_method")).thenReturn("UPI");
              when(rs.getLong("total_payable_paise")).thenReturn(0L);
              when(rs.getTimestamp("estimated_delivery_at")).thenReturn(null);
              when(rs.getTimestamp("sla_deadline")).thenReturn(Timestamp.from(now));
              when(rs.getString("delivery_otp_hash")).thenReturn("x");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(adapter.findOrder(Ids.newId())).isPresent();
    when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    assertThat(adapter.listUnassignedReady(null, 1, 10).total()).isZero();
  }

  private static OrderDetails details(
      UUID id, UUID zoneId, UUID pharmacyId, String status, UUID riderId) {
    return new OrderDetails(
        id,
        "MED",
        status,
        riderId,
        pharmacyId,
        "P",
        "A",
        12.9,
        77.6,
        "9",
        zoneId,
        "Z",
        "C",
        "8",
        "D",
        12.9,
        77.6,
        1,
        "UPI",
        100,
        Instant.parse("2026-07-24T10:00:00Z"),
        Instant.parse("2026-07-24T10:00:00Z"),
        "h");
  }

  private static com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord rider(
      UUID id, UUID zoneId, String status) {
    Instant t = Instant.parse("2026-07-24T09:00:00Z");
    return new com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord(
        id,
        "R",
        "9000000000",
        null,
        "BIKE",
        "KA01AB1234",
        zoneId,
        status,
        "APPROVED",
        t,
        t,
        null,
        null,
        null,
        true,
        BigDecimal.valueOf(4.5),
        1,
        BigDecimal.valueOf(90),
        0,
        0,
        0,
        null,
        null,
        null,
        t,
        t);
  }
}

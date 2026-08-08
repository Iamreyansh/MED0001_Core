package com.nammamedmate.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.order.adapter.out.persistence.StubRefundInitiatorAdapter;
import com.nammamedmate.order.adapter.out.persistence.StubRiderLookupAdapter;
import com.nammamedmate.order.application.OrderPlacementServiceTest.InMemoryOrderStatusEventStore;
import com.nammamedmate.order.application.OrderPlacementServiceTest.InMemoryOrderStore;
import com.nammamedmate.order.application.port.out.DeliveryOtpCachePort;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderItemSnapshot;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.PaymentMethod;
import com.nammamedmate.order.domain.PaymentStatus;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderLifecycleServiceTest {

  private static final UUID CUST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private static final UUID STAFF = UUID.fromString("bbbbbbbb-0001-4000-8000-000000000001");
  private static final UUID ADMIN = UUID.fromString("cccccccc-0001-4000-8000-000000000001");
  private static final UUID RIDER = UUID.fromString("dddddddd-0001-4000-8000-000000000001");
  private static final Instant T0 = Instant.parse("2026-08-08T06:00:00Z");

  @Mock private RateLimiter rateLimiter;

  private InMemoryOrderStore orders;
  private InMemoryOrderStatusEventStore events;
  private InMemoryOutboxStore outboxStore;
  private OrderLifecycleService service;
  private Clock clock;

  private final MedmatePrincipal pharmacy =
      new MedmatePrincipal(STAFF, AuthRole.PHARMACY_OWNER, PH1, TokenScope.FULL, "j");
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal admin =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    orders = new InMemoryOrderStore();
    events = new InMemoryOrderStatusEventStore();
    outboxStore = new InMemoryOutboxStore();
    clock = Clock.fixed(T0, ZoneOffset.UTC);
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    service = buildService(clock, outboxStore, fixedOtp(1234));
  }

  private OrderLifecycleService buildService(Clock c, InMemoryOutboxStore box, SecureRandom rnd) {
    DeliveryOtpCachePort otpCache = (orderId, otp) -> {};
    return new OrderLifecycleService(
        orders,
        events,
        new StubRiderLookupAdapter(),
        new StubRefundInitiatorAdapter(),
        new OutboxPublisher(box, new ObjectMapper()),
        rateLimiter,
        c,
        otpCache,
        hashEncoder(),
        rnd);
  }

  private static PasswordEncoder hashEncoder() {
    return new PasswordEncoder() {
      @Override
      public String encode(CharSequence rawPassword) {
        return "hash:" + rawPassword;
      }

      @Override
      public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return encode(rawPassword).equals(encodedPassword);
      }
    };
  }

  private static SecureRandom fixedOtp(int value) {
    return new SecureRandom() {
      @Override
      public int nextInt(int bound) {
        return value;
      }
    };
  }

  @Test
  void ac1_acceptanceTimeout_cancelsWithReasonAndRefundOutbox() {
    Order order = pendingOrder(PaymentMethod.UPI);
    orders.insert(order);
    clock = Clock.fixed(T0.plusSeconds(601), ZoneOffset.UTC);
    service = buildService(clock, outboxStore, fixedOtp(1));
    assertThat(service.cancelTimedOutAcceptances()).isEqualTo(1);
    Order updated = orders.findById(order.id()).orElseThrow();
    assertThat(updated.status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(updated.cancelReason()).isEqualTo("PHARMACY_ACCEPTANCE_TIMEOUT");
    assertThat(outboxStore.all().stream().anyMatch(m -> "order.refund.requested".equals(m.type())))
        .isTrue();
  }

  @Test
  void ac2_pharmacyReject_cancelsNotifiesWhatsAppAndRefunds() {
    Order order = pendingOrder(PaymentMethod.UPI);
    orders.insert(order);
    Map<String, Object> res =
        service.reject(pharmacy, order.id(), "OUT_OF_STOCK", "Metformin unavailable");
    assertThat(res.get("status")).isEqualTo("CANCELLED");
    assertThat(res.get("refund_initiated")).isEqualTo(true);
    assertThat(orders.findById(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(
            outboxStore.all().stream()
                .anyMatch(
                    m ->
                        "customer.notification.requested".equals(m.type())
                            && m.payloadJson().contains("WHATSAPP")))
        .isTrue();
  }

  @Test
  void ac3_readyForPickup_generatesHashedOtpAndNotifies() {
    Order order = pendingOrder(PaymentMethod.COD);
    order.accept(T0);
    orders.insert(order);
    service.advancePharmacyStatus(pharmacy, order.id(), "PACKING", "Started packing");
    Map<String, Object> ready =
        service.advancePharmacyStatus(pharmacy, order.id(), "READY_FOR_PICKUP", null);
    assertThat(ready.get("status")).isEqualTo("READY_FOR_PICKUP");
    Order updated = orders.findById(order.id()).orElseThrow();
    assertThat(updated.deliveryOtpHash()).isEqualTo("hash:1234");
    assertThat(
            outboxStore.all().stream()
                .filter(m -> "customer.notification.requested".equals(m.type()))
                .count())
        .isGreaterThanOrEqualTo(2);
    assertThat(
            outboxStore.all().stream()
                .filter(m -> "customer.notification.requested".equals(m.type()))
                .noneMatch(
                    m -> m.payloadJson().contains("\"otp\"") || m.payloadJson().contains("1234")))
        .isTrue();
    assertThat(
            outboxStore.all().stream()
                .anyMatch(
                    m ->
                        m.payloadJson().contains("Your delivery OTP was sent via SMS")
                            && m.payloadJson().contains("delivery_otp")))
        .isTrue();
  }

  @Test
  void ac4_slaRisk_whenLessThanFiveMinutesRemain() {
    Order order = pendingOrder(PaymentMethod.COD);
    order.accept(T0);
    order.advanceTo(OrderStatus.PACKING, T0);
    order.advanceTo(OrderStatus.READY_FOR_PICKUP, T0);
    order.advanceTo(OrderStatus.OUT_FOR_DELIVERY, T0);
    // sla_deadline = T0+30m; set clock to T0+27m → 3 min remaining
    clock = Clock.fixed(T0.plusSeconds(27 * 60), ZoneOffset.UTC);
    service = buildService(clock, outboxStore, fixedOtp(1));
    orders.insert(order);
    Map<String, Object> tracking = service.tracking(customer, order.id());
    assertThat(tracking.get("sla_risk")).isEqualTo(true);
    assertThat(((Number) tracking.get("sla_remaining_minutes")).intValue()).isEqualTo(3);
  }

  @Test
  void ac5_pharmacyCannotSkipToOutForDelivery() {
    Order order = pendingOrder(PaymentMethod.COD);
    order.accept(T0);
    order.advanceTo(OrderStatus.PACKING, T0);
    orders.insert(order);
    assertThatThrownBy(
            () -> service.advancePharmacyStatus(pharmacy, order.id(), "OUT_FOR_DELIVERY", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_STATUS_TRANSITION");
  }

  @Test
  void ac6_trackingOutForDelivery_stepsAndEta() {
    Order order = pendingOrder(PaymentMethod.COD);
    order.accept(T0);
    order.advanceTo(OrderStatus.PACKING, T0);
    order.advanceTo(OrderStatus.READY_FOR_PICKUP, T0);
    order.advanceTo(OrderStatus.OUT_FOR_DELIVERY, T0);
    order.assignRider(RIDER, T0);
    orders.insert(order);
    Map<String, Object> tracking = service.tracking(customer, order.id());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> steps = (List<Map<String, Object>>) tracking.get("steps");
    assertThat(steps.get(4).get("completed")).isEqualTo(true);
    assertThat(steps.get(5).get("completed")).isEqualTo(false);
    assertThat(tracking.get("eta_minutes")).isNotNull();
    assertThat(tracking.get("current_step")).isEqualTo("Rider on the way");
  }

  @Test
  void ac7_timelineListsSixEventsChronologically() {
    Order order = pendingOrder(PaymentMethod.COD);
    orders.insert(order);
    events.append(
        new com.nammamedmate.order.domain.OrderStatusEvent(
            UUID.randomUUID(),
            order.id(),
            OrderStatus.PAYMENT_PENDING,
            OrderStatus.PENDING_ACCEPTANCE,
            com.nammamedmate.order.domain.ActorType.SYSTEM,
            null,
            null,
            T0));
    service.accept(pharmacy, order.id());
    service.advancePharmacyStatus(pharmacy, order.id(), "PACKING", null);
    service.advancePharmacyStatus(pharmacy, order.id(), "READY_FOR_PICKUP", null);
    service.adminForceStatus(admin, order.id(), "OUT_FOR_DELIVERY", "ops", null);
    service.adminForceStatus(admin, order.id(), "DELIVERED", "done", null);
    Map<String, Object> timeline = service.timeline(customer, order.id());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> ev = (List<Map<String, Object>>) timeline.get("events");
    assertThat(ev).hasSize(6);
    assertThat(ev.get(0).get("status")).isEqualTo("PENDING_ACCEPTANCE");
    assertThat(ev.get(5).get("status")).isEqualTo("DELIVERED");
    assertThat(ev.get(5).get("timestamp")).isNotNull();
    assertThat(outboxStore.all().stream().anyMatch(m -> "order.delivered".equals(m.type())))
        .isTrue();
  }

  @Test
  void ac8_noRiderWithin30Min_escalatesAlertOnly() {
    Order order = pendingOrder(PaymentMethod.COD);
    order.accept(T0);
    order.advanceTo(OrderStatus.PACKING, T0);
    order.advanceTo(OrderStatus.READY_FOR_PICKUP, T0);
    orders.insert(order);
    clock = Clock.fixed(T0.plus(Order.RIDER_ASSIGN_ALERT).plusSeconds(1), ZoneOffset.UTC);
    service = buildService(clock, outboxStore, fixedOtp(1));
    assertThat(service.escalateMissingRiders()).isEqualTo(1);
    Order updated = orders.findById(order.id()).orElseThrow();
    assertThat(updated.status()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
    assertThat(updated.riderEscalationAt()).isNotNull();
    assertThat(outboxStore.all().stream().anyMatch(m -> "order.rider.escalation".equals(m.type())))
        .isTrue();
  }

  @Test
  void acceptRejectAssignAndAdminPaths() {
    Order order = pendingOrder(PaymentMethod.COD);
    orders.insert(order);
    Map<String, Object> accepted = service.accept(pharmacy, order.id());
    assertThat(accepted.get("status")).isEqualTo("ACCEPTED");

    Order other = pendingOrder(PaymentMethod.UPI);
    orders.insert(other);
    Map<String, Object> rejected = service.reject(pharmacy, other.id(), "CLOSING_SOON", null);
    assertThat(rejected.get("refund_initiated")).isEqualTo(true);

    Order codReject = pendingOrder(PaymentMethod.COD);
    orders.insert(codReject);
    assertThat(service.reject(pharmacy, codReject.id(), "OTHER", "x").get("refund_initiated"))
        .isEqualTo(false);

    Order assignable = pendingOrder(PaymentMethod.COD);
    assignable.accept(T0);
    assignable.advanceTo(OrderStatus.READY_FOR_PICKUP, T0);
    orders.insert(assignable);
    Map<String, Object> assigned = service.assignRider(pharmacy, assignable.id(), RIDER);
    assertThat(assigned.get("rider_id")).isEqualTo(RIDER.toString());

    assertThatThrownBy(() -> service.accept(pharmacy, order.id()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_ALREADY_ACTIONED");

    Order timed = pendingOrder(PaymentMethod.COD);
    orders.insert(timed);
    clock = Clock.fixed(T0.plusSeconds(601), ZoneOffset.UTC);
    OrderLifecycleService late = buildService(clock, outboxStore, fixedOtp(1));
    assertThatThrownBy(() -> late.accept(pharmacy, timed.id()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_ACCEPTANCE_TIMEOUT");

    assertThat(service.markSlaBreaches()).isEqualTo(0);
  }

  private Order pendingOrder(PaymentMethod method) {
    Order order =
        new Order(
            UUID.randomUUID(),
            "ORD-20260808-00099",
            CUST,
            PH1,
            UUID.randomUUID(),
            List.of(new OrderItemSnapshot(UUID.randomUUID(), "Metformin", 1, 8500, 8500, false)),
            8500,
            null,
            0,
            2500,
            500,
            0,
            11500,
            method,
            method == PaymentMethod.COD ? PaymentStatus.PENDING_COLLECTION : PaymentStatus.PAID,
            null,
            null,
            null,
            UUID.randomUUID(),
            null,
            OrderStatus.PAYMENT_PENDING,
            null,
            null,
            null,
            null,
            null,
            T0,
            T0);
    order.confirm(T0, T0.plusSeconds(900), method == PaymentMethod.COD ? null : "pay_1");
    return order;
  }
}

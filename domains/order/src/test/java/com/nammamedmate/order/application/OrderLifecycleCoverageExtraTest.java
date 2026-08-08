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
import com.nammamedmate.order.application.OrderPlacementServiceTest.InMemoryOrderStatusEventStore;
import com.nammamedmate.order.application.OrderPlacementServiceTest.InMemoryOrderStore;
import com.nammamedmate.order.application.port.out.RiderLookupPort;
import com.nammamedmate.order.domain.ActorType;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.OrderStatusEvent;
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
import java.util.Optional;
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
class OrderLifecycleCoverageExtraTest {

  private static final UUID CUST = UUID.randomUUID();
  private static final UUID PH = UUID.randomUUID();
  private static final Instant T0 = Instant.parse("2026-08-08T06:00:00Z");

  @Mock private RateLimiter rateLimiter;
  private InMemoryOrderStore orders;
  private InMemoryOrderStatusEventStore events;
  private OrderLifecycleService service;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal staff =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.PHARMACY_STAFF, PH, TokenScope.FULL, "j");
  private final MedmatePrincipal adminSuper =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    orders = new InMemoryOrderStore();
    events = new InMemoryOrderStatusEventStore();
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    service = build(new AlwaysFoundRider(), Clock.fixed(T0, ZoneOffset.UTC));
  }

  @Test
  void coversRemainingBranches() {
    assertThatThrownBy(() -> service.tracking(null, UUID.randomUUID()))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.accept(null, UUID.randomUUID()))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                service.accept(
                    new MedmatePrincipal(
                        UUID.randomUUID(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j"),
                    UUID.randomUUID()))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.adminForceStatus(null, UUID.randomUUID(), "X", "r", null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.timeline(customer, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.tracking(customer, UUID.randomUUID()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");

    for (OrderStatus status : OrderStatus.values()) {
      Order o = base(status);
      orders.insert(o);
      Map<String, Object> tracking = service.tracking(customer, o.id());
      assertThat(tracking.get("current_step")).isNotNull();
      assertThat(tracking.get("steps")).isInstanceOf(List.class);
    }

    Order cancelled = base(OrderStatus.CANCELLED);
    cancelled.cancel("X", T0);
    orders.insert(cancelled);
    events.append(
        new OrderStatusEvent(
            UUID.randomUUID(),
            cancelled.id(),
            OrderStatus.PENDING_ACCEPTANCE,
            OrderStatus.CANCELLED,
            ActorType.SYSTEM,
            null,
            null,
            T0));
    assertThat(service.tracking(customer, cancelled.id()).get("status")).isEqualTo("CANCELLED");

    Order noConfirm =
        new Order(
            UUID.randomUUID(),
            "ORD-NC",
            CUST,
            PH,
            UUID.randomUUID(),
            List.of(),
            0,
            null,
            0,
            0,
            0,
            0,
            0,
            PaymentMethod.COD,
            PaymentStatus.PENDING_COLLECTION,
            null,
            null,
            null,
            UUID.randomUUID(),
            null,
            OrderStatus.PENDING_ACCEPTANCE,
            null,
            null,
            null,
            null,
            null,
            T0,
            T0);
    orders.insert(noConfirm);
    assertThat(service.tracking(customer, noConfirm.id()).get("steps")).isNotNull();

    Order pending = base(OrderStatus.PENDING_ACCEPTANCE);
    orders.insert(pending);
    assertThat(service.reject(staff, pending.id(), "OUT_OF_STOCK", "  ").get("status"))
        .isEqualTo("CANCELLED");
    Order pending2 = base(OrderStatus.ACCEPTED);
    orders.insert(pending2);
    assertThatThrownBy(() -> service.reject(staff, pending2.id(), "OTHER", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_ALREADY_ACTIONED");

    Order packing = base(OrderStatus.PACKING);
    packing.confirm(T0, T0.plusSeconds(600), null);
    packing.accept(T0);
    packing.advanceTo(OrderStatus.PACKING, T0);
    orders.insert(packing);
    Map<String, Object> assigned = service.assignRider(staff, packing.id(), UUID.randomUUID());
    assertThat(assigned.get("rider_id")).isNotNull();

    Order accepted = base(OrderStatus.ACCEPTED);
    accepted.confirm(T0, T0.plusSeconds(600), null);
    accepted.accept(T0);
    orders.insert(accepted);
    service.assignRider(staff, accepted.id(), UUID.randomUUID());

    OrderLifecycleService missingRider =
        build(riderId -> Optional.empty(), Clock.fixed(T0, ZoneOffset.UTC));
    Order ready = base(OrderStatus.READY_FOR_PICKUP);
    ready.confirm(T0, T0.plusSeconds(600), null);
    ready.accept(T0);
    ready.advanceTo(OrderStatus.READY_FOR_PICKUP, T0);
    orders.insert(ready);
    assertThatThrownBy(() -> missingRider.assignRider(staff, ready.id(), UUID.randomUUID()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    Order force = base(OrderStatus.PENDING_ACCEPTANCE);
    force.confirm(T0, T0.plusSeconds(600), null);
    orders.insert(force);
    service.adminForceStatus(adminSuper, force.id(), "ACCEPTED", "reason", "  ");
    Order force2 = base(OrderStatus.PENDING_ACCEPTANCE);
    force2.confirm(T0, T0.plusSeconds(600), null);
    orders.insert(force2);
    service.adminForceStatus(adminSuper, force2.id(), "PACKING", "reason", "notes");

    assertThatThrownBy(() -> service.advancePharmacyStatus(staff, force2.id(), " ", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.reject(staff, force2.id(), " ", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.adminForceStatus(adminSuper, force2.id(), "ACCEPTED", "   ", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    Order force3 = base(OrderStatus.PENDING_ACCEPTANCE);
    orders.insert(force3);
    service.adminForceStatus(adminSuper, force3.id(), "ACCEPTED", "reason", null);
  }

  private OrderLifecycleService build(RiderLookupPort riders, Clock clock) {
    PasswordEncoder enc =
        new PasswordEncoder() {
          @Override
          public String encode(CharSequence rawPassword) {
            return "h:" + rawPassword;
          }

          @Override
          public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return true;
          }
        };
    return new OrderLifecycleService(
        orders,
        events,
        riders,
        new StubRefundInitiatorAdapter(),
        new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
        rateLimiter,
        clock,
        (orderId, otp) -> {},
        enc,
        new SecureRandom());
  }

  private Order base(OrderStatus status) {
    Order order =
        new Order(
            UUID.randomUUID(),
            "ORD-X",
            CUST,
            PH,
            UUID.randomUUID(),
            List.of(),
            0,
            null,
            0,
            0,
            0,
            0,
            100,
            PaymentMethod.COD,
            PaymentStatus.PENDING_COLLECTION,
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
    if (status == OrderStatus.PAYMENT_PENDING) {
      return order;
    }
    order.confirm(T0, T0.plusSeconds(900), null);
    if (status == OrderStatus.PENDING_ACCEPTANCE) {
      return order;
    }
    order.accept(T0);
    if (status == OrderStatus.ACCEPTED) {
      return order;
    }
    if (status == OrderStatus.CANCELLED) {
      order.cancel("R", T0);
      return order;
    }
    order.advanceTo(status, T0);
    return order;
  }

  private static final class AlwaysFoundRider implements RiderLookupPort {
    @Override
    public Optional<RiderInfo> findById(UUID riderId) {
      return Optional.of(new RiderInfo(riderId, "R", "+91", "KA", null));
    }
  }
}

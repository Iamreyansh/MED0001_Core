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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderLifecycleGapsTest {

  private static final UUID CUST = UUID.randomUUID();
  private static final UUID PH = UUID.randomUUID();
  private static final Instant T0 = Instant.parse("2026-08-08T06:00:00Z");

  @Mock private RateLimiter rateLimiter;
  private InMemoryOrderStore orders;
  private InMemoryOrderStatusEventStore events;
  private OrderLifecycleService service;
  private final MedmatePrincipal pharmacy =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PH, TokenScope.FULL, "j");
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal admin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    orders = new InMemoryOrderStore();
    events = new InMemoryOrderStatusEventStore();
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    service =
        new OrderLifecycleService(
            orders,
            events,
            new StubRiderLookupAdapter(),
            new StubRefundInitiatorAdapter(),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            rateLimiter,
            Clock.fixed(T0, ZoneOffset.UTC),
            (orderId, otp) -> {});
  }

  @Test
  void validationAuthAndCancelAdminBranches() {
    // public ctor uses BCrypt
    assertThat(new BCryptPasswordEncoder(10).encode("0000")).isNotBlank();

    assertThatThrownBy(() -> service.accept(customer, UUID.randomUUID()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.tracking(pharmacy, UUID.randomUUID()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.adminForceStatus(customer, UUID.randomUUID(), "X", "r", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");

    assertThatThrownBy(() -> service.accept(pharmacy, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.accept(pharmacy, UUID.randomUUID()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");

    assertThatThrownBy(() -> service.advancePharmacyStatus(pharmacy, UUID.randomUUID(), null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.advancePharmacyStatus(pharmacy, UUID.randomUUID(), "NOPE", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.reject(pharmacy, UUID.randomUUID(), null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.reject(pharmacy, UUID.randomUUID(), "BAD", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.assignRider(pharmacy, UUID.randomUUID(), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.adminForceStatus(admin, UUID.randomUUID(), "ACCEPTED", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.adminForceStatus(admin, UUID.randomUUID(), "ACCEPTED", "r", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");

    Order order = pending();
    orders.insert(order);
    assertThatThrownBy(
            () -> service.advancePharmacyStatus(pharmacy, order.id(), "PACKING", "x".repeat(301)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(false);
    when(rateLimiter.secondsUntilAvailable(any(), anyInt(), anyInt())).thenReturn(5);
    assertThatThrownBy(() -> service.accept(pharmacy, order.id()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);

    service.accept(pharmacy, order.id());
    assertThatThrownBy(
            () -> service.advancePharmacyStatus(pharmacy, order.id(), "READY_FOR_PICKUP", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_STATUS_TRANSITION");

    Order delivered = pending();
    delivered.accept(T0);
    delivered.advanceTo(OrderStatus.DELIVERED, T0);
    orders.insert(delivered);
    assertThatThrownBy(
            () -> service.adminForceStatus(admin, delivered.id(), "CANCELLED", "late", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_STATUS_TRANSITION");

    Order cancelMe = pending();
    orders.insert(cancelMe);
    service.adminForceStatus(admin, cancelMe.id(), "CANCELLED", "ops cancel", "note");
    assertThat(orders.findById(cancelMe.id()).orElseThrow().status())
        .isEqualTo(OrderStatus.CANCELLED);

    Order paymentPending =
        new Order(
            UUID.randomUUID(),
            "ORD-P",
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
            PaymentMethod.UPI,
            PaymentStatus.AWAITING_PAYMENT,
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
    orders.insert(paymentPending);
    assertThat(service.tracking(customer, paymentPending.id()).get("current_step"))
        .isEqualTo("Awaiting payment");

    Order assignWrong = pending();
    orders.insert(assignWrong);
    assertThatThrownBy(() -> service.assignRider(pharmacy, assignWrong.id(), UUID.randomUUID()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_STATUS_TRANSITION");

    // mark sla breach path
    Order open = pending();
    orders.insert(open);
    OrderLifecycleService late =
        new OrderLifecycleService(
            orders,
            events,
            new StubRiderLookupAdapter(),
            new StubRefundInitiatorAdapter(),
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            rateLimiter,
            Clock.fixed(T0.plusSeconds(31 * 60), ZoneOffset.UTC),
            (orderId, otp) -> {},
            new BCryptPasswordEncoder(4),
            new SecureRandom());
    assertThat(late.markSlaBreaches()).isGreaterThanOrEqualTo(1);
  }

  private Order pending() {
    Order order =
        new Order(
            UUID.randomUUID(),
            "ORD-G",
            CUST,
            PH,
            UUID.randomUUID(),
            List.of(new OrderItemSnapshot(UUID.randomUUID(), "M", 1, 1, 1, false)),
            1,
            null,
            0,
            0,
            0,
            0,
            1,
            PaymentMethod.UPI,
            PaymentStatus.PAID,
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
    order.confirm(T0, T0.plusSeconds(600), "pay");
    return order;
  }
}

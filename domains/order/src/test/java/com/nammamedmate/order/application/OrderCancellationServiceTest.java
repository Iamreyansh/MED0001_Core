package com.nammamedmate.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.order.application.port.out.OrderStatusEventStore;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.application.port.out.RefundInitiatorPort.RefundPlan;
import com.nammamedmate.order.domain.ActorType;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.PaymentMethod;
import com.nammamedmate.order.domain.PaymentStatus;
import com.nammamedmate.order.domain.Refund;
import com.nammamedmate.order.domain.RefundIssuedByType;
import com.nammamedmate.order.domain.RefundStatus;
import com.nammamedmate.order.domain.RefundTo;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderCancellationServiceTest {

  private static final Instant T0 = Instant.parse("2026-08-08T06:00:00Z");
  private static final UUID CUST = UUID.randomUUID();
  private static final UUID ADMIN = UUID.randomUUID();

  private OrderStore orders;
  private OrderStatusEventStore events;
  private RefundService refunds;
  private InMemoryOutboxStore outboxStore;
  private com.nammamedmate.kernel.ratelimit.RateLimiter rateLimiter;
  private OrderCancellationService service;

  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal admin =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  private final MedmatePrincipal finance =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    orders = mock(OrderStore.class);
    events = mock(OrderStatusEventStore.class);
    refunds = mock(RefundService.class);
    outboxStore = new InMemoryOutboxStore();
    rateLimiter = mock(com.nammamedmate.kernel.ratelimit.RateLimiter.class);
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    service =
        new OrderCancellationService(
            orders,
            events,
            refunds,
            new OutboxPublisher(outboxStore, new ObjectMapper()),
            rateLimiter,
            Clock.fixed(T0, ZoneOffset.UTC));
  }

  @Test
  void ac1_customerCannotCancelPacking() {
    Order packing = order(OrderStatus.PACKING, PaymentMethod.UPI, PaymentStatus.PAID, 22125);
    when(orders.findByCustomerAndId(CUST, packing.id())).thenReturn(Optional.of(packing));
    assertThatThrownBy(() -> service.customerCancel(customer, packing.id(), "CHANGED_MIND"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_CANNOT_CANCEL");
  }

  @Test
  void ac2_customerCancelUpi_initiatesSourceRefund() {
    Order upi = order(OrderStatus.ACCEPTED, PaymentMethod.UPI, PaymentStatus.PAID, 22125);
    when(orders.findByCustomerAndId(CUST, upi.id())).thenReturn(Optional.of(upi));
    when(refunds.initiate(eq(upi), eq("CHANGED_MIND"), eq(ActorType.CUSTOMER), eq(CUST)))
        .thenReturn(new RefundPlan(true, 22125, "SOURCE"));
    Map<String, Object> data = service.customerCancel(customer, upi.id(), "CHANGED_MIND");
    assertThat(data.get("status")).isEqualTo("CANCELLED");
    @SuppressWarnings("unchecked")
    Map<String, Object> refund = (Map<String, Object>) data.get("refund");
    assertThat(refund.get("initiated")).isEqualTo(true);
    assertThat(refund.get("refund_to")).isEqualTo("SOURCE");
    assertThat(
            outboxStore.all().stream()
                .anyMatch(m -> "customer.notification.requested".equals(m.type())))
        .isTrue();
    assertThat(outboxStore.all().stream().anyMatch(m -> "order.refund.requested".equals(m.type())))
        .isTrue();
  }

  @Test
  void ac3_adminCancelCod_noRefund() {
    Order cod =
        order(OrderStatus.ACCEPTED, PaymentMethod.COD, PaymentStatus.PENDING_COLLECTION, 22125);
    when(orders.findById(cod.id())).thenReturn(Optional.of(cod));
    when(refunds.issueOnAdminCancel(any(), anyLong(), any(), any(), any(), any())).thenReturn(null);
    Map<String, Object> data = service.adminCancel(admin, cod.id(), "ops cancel", 0, "WALLET");
    @SuppressWarnings("unchecked")
    Map<String, Object> refund = (Map<String, Object>) data.get("refund");
    assertThat(refund.get("initiated")).isEqualTo(false);
  }

  @Test
  void ac5_adminRefundExceeds() {
    Order upi = order(OrderStatus.DELIVERED, PaymentMethod.UPI, PaymentStatus.PAID, 22125);
    when(orders.findById(upi.id())).thenReturn(Optional.of(upi));
    when(refunds.issueManual(any(), eq(25000L), any(), any(), any(), any(), any()))
        .thenThrow(new AppException("REFUND_EXCEEDS_REMAINING_REFUNDABLE", "exceeds", 422));
    assertThatThrownBy(
            () -> service.adminRefund(admin, upi.id(), 250.00, "WALLET", "too much", null, "idem"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFUND_EXCEEDS_REMAINING_REFUNDABLE");
  }

  @Test
  void ac4_adminPartialWalletRefund() {
    Order delivered = order(OrderStatus.DELIVERED, PaymentMethod.UPI, PaymentStatus.PAID, 22125);
    when(orders.findById(delivered.id())).thenReturn(Optional.of(delivered));
    Refund refund =
        new Refund(
            UUID.randomUUID(),
            delivered.id(),
            5000,
            RefundTo.WALLET,
            "missing",
            "notes",
            RefundStatus.PROCESSED,
            ADMIN,
            RefundIssuedByType.ADMIN,
            null,
            UUID.randomUUID(),
            T0,
            null,
            "idem",
            T0);
    when(refunds.issueManual(
            eq(delivered),
            eq(5000L),
            eq(RefundTo.WALLET),
            eq("missing"),
            eq("notes"),
            eq(ADMIN),
            eq("idem")))
        .thenReturn(refund);
    Map<String, Object> data =
        service.adminRefund(admin, delivered.id(), 50.00, "WALLET", "missing", "notes", "idem");
    assertThat(data.get("status")).isEqualTo("PROCESSED");
    assertThat(data.get("refund_to")).isEqualTo("WALLET");
  }

  @Test
  void ac7_adminCannotCancelDelivered() {
    Order delivered = order(OrderStatus.DELIVERED, PaymentMethod.UPI, PaymentStatus.PAID, 100);
    when(orders.findById(delivered.id())).thenReturn(Optional.of(delivered));
    assertThatThrownBy(() -> service.adminCancel(admin, delivered.id(), "nope", 100, "SOURCE"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_DELIVERED");
  }

  @Test
  void financeCannotCancel_butCanRefundEligibility() {
    assertThatThrownBy(() -> service.adminCancel(finance, UUID.randomUUID(), "x", 1, "SOURCE"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");

    Order upi = order(OrderStatus.ACCEPTED, PaymentMethod.UPI, PaymentStatus.PAID, 22125);
    when(orders.findById(upi.id())).thenReturn(Optional.of(upi));
    when(refunds.alreadyRefundedPaise(upi.id())).thenReturn(0L);
    when(refunds.remainingRefundablePaise(upi)).thenReturn(22125L);
    when(refunds.recommendRefundTo(upi)).thenReturn(RefundTo.SOURCE);
    Map<String, Object> elig = service.refundEligibility(finance, upi.id());
    assertThat(elig.get("eligible")).isEqualTo(true);
    assertThat(elig.get("cancellation_eligible")).isEqualTo(true);
  }

  @Test
  void customerAlreadyCancelledAndValidation() {
    Order cancelled = order(OrderStatus.CANCELLED, PaymentMethod.UPI, PaymentStatus.REFUNDED, 100);
    when(orders.findByCustomerAndId(CUST, cancelled.id())).thenReturn(Optional.of(cancelled));
    assertThatThrownBy(() -> service.customerCancel(customer, cancelled.id(), "CHANGED_MIND"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_ALREADY_CANCELLED");
    assertThatThrownBy(() -> service.customerCancel(customer, UUID.randomUUID(), "BAD"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.customerCancel(customer, null, "CHANGED_MIND"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void adminCancelWithRefundAndWalletReverse() {
    Order upi = order(OrderStatus.PACKING, PaymentMethod.UPI, PaymentStatus.PAID, 10000);
    // simulate wallet applied via new order helper - rebuild
    upi =
        new Order(
            upi.id(),
            upi.orderNumber(),
            CUST,
            upi.pharmacyId(),
            upi.cartId(),
            List.of(),
            15000,
            null,
            0,
            2500,
            500,
            5000,
            10000,
            PaymentMethod.UPI,
            PaymentStatus.PAID,
            "rz",
            "pay",
            null,
            UUID.randomUUID(),
            null,
            OrderStatus.PACKING,
            null,
            null,
            null,
            T0,
            T0,
            T0,
            T0);
    when(orders.findById(upi.id())).thenReturn(Optional.of(upi));
    Refund refund =
        new Refund(
            UUID.randomUUID(),
            upi.id(),
            8000,
            RefundTo.SOURCE,
            "ops",
            null,
            RefundStatus.INITIATED,
            ADMIN,
            RefundIssuedByType.ADMIN,
            "rfnd_1",
            null,
            null,
            null,
            null,
            T0);
    when(refunds.issueOnAdminCancel(
            eq(upi), eq(8000L), eq(RefundTo.SOURCE), eq("ops"), eq(ADMIN), eq(T0)))
        .thenReturn(refund);
    Map<String, Object> data = service.adminCancel(admin, upi.id(), "ops", 80.00, "SOURCE");
    verify(refunds).reverseWalletApplied(eq(upi), eq("ops"), eq(ADMIN), eq(T0));
    @SuppressWarnings("unchecked")
    Map<String, Object> refundView = (Map<String, Object>) data.get("refund");
    assertThat(refundView.get("initiated")).isEqualTo(true);
    assertThat(refundView.get("refund_id")).isEqualTo(refund.id().toString());
  }

  @Test
  void customerCancelNoRefundMessage() {
    Order pending =
        order(
            OrderStatus.PENDING_ACCEPTANCE,
            PaymentMethod.COD,
            PaymentStatus.PENDING_COLLECTION,
            100);
    when(orders.findByCustomerAndId(CUST, pending.id())).thenReturn(Optional.of(pending));
    when(refunds.initiate(any(), any(), any(), any())).thenReturn(new RefundPlan(false, 0, null));
    Map<String, Object> data = service.customerCancel(customer, pending.id(), "OTHER");
    @SuppressWarnings("unchecked")
    Map<String, Object> refund = (Map<String, Object>) data.get("refund");
    assertThat(refund.get("message")).asString().contains("No refund");
  }

  @Test
  void rateLimitAndAuth() {
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(false);
    when(rateLimiter.secondsUntilAvailable(any(), anyInt(), anyInt())).thenReturn(3);
    assertThatThrownBy(() -> service.customerCancel(customer, UUID.randomUUID(), "CHANGED_MIND"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    assertThatThrownBy(
            () -> service.adminRefund(customer, UUID.randomUUID(), 1, "W", "r", null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.customerCancel(null, UUID.randomUUID(), "CHANGED_MIND"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void coverageGaps_validationEligibilityAndWalletMessage() {
    assertThatThrownBy(() -> service.adminCancel(admin, UUID.randomUUID(), null, 1, "SOURCE"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.adminCancel(admin, UUID.randomUUID(), "x".repeat(301), 1, "SOURCE"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(orders.findById(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.adminCancel(admin, UUID.randomUUID(), "r", 1, "SOURCE"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");

    Order cancelled = order(OrderStatus.CANCELLED, PaymentMethod.UPI, PaymentStatus.REFUNDED, 100);
    when(orders.findById(cancelled.id())).thenReturn(Optional.of(cancelled));
    assertThatThrownBy(() -> service.adminCancel(admin, cancelled.id(), "r", 1, "SOURCE"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_ALREADY_CANCELLED");

    Order packing = order(OrderStatus.PACKING, PaymentMethod.UPI, PaymentStatus.PAID, 1000);
    when(orders.findById(packing.id())).thenReturn(Optional.of(packing));
    assertThatThrownBy(() -> service.adminCancel(admin, packing.id(), "r", 50.00, "SOURCE"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFUND_EXCEEDS_ORDER_TOTAL");

    Order accepted = order(OrderStatus.ACCEPTED, PaymentMethod.UPI, PaymentStatus.PAID, 1000);
    when(orders.findById(accepted.id())).thenReturn(Optional.of(accepted));
    assertThatThrownBy(() -> service.adminCancel(admin, accepted.id(), "r", 20.00, "SOURCE"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFUND_EXCEEDS_ORDER_TOTAL");

    assertThatThrownBy(
            () -> service.adminRefund(admin, UUID.randomUUID(), 1, "WALLET", null, null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.adminRefund(
                    admin, UUID.randomUUID(), 1, "WALLET", "x".repeat(301), null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.adminRefund(
                    admin, UUID.randomUUID(), 1, "WALLET", "ok", "n".repeat(501), "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(orders.findById(any())).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> service.adminRefund(admin, UUID.randomUUID(), 1, "WALLET", "ok", null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");
    assertThatThrownBy(() -> service.refundEligibility(admin, UUID.randomUUID()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");

    Order walletPaid = order(OrderStatus.ACCEPTED, PaymentMethod.WALLET, PaymentStatus.PAID, 0);
    when(orders.findByCustomerAndId(CUST, walletPaid.id())).thenReturn(Optional.of(walletPaid));
    when(refunds.initiate(eq(walletPaid), eq("CHANGED_MIND"), eq(ActorType.CUSTOMER), eq(CUST)))
        .thenReturn(new RefundPlan(true, 5000, "WALLET"));
    Map<String, Object> walletCancel =
        service.customerCancel(customer, walletPaid.id(), "CHANGED_MIND");
    @SuppressWarnings("unchecked")
    Map<String, Object> wr = (Map<String, Object>) walletCancel.get("refund");
    assertThat(wr.get("message")).asString().contains("Namma Money");

    Order delivered = order(OrderStatus.DELIVERED, PaymentMethod.COD, PaymentStatus.COLLECTED, 100);
    when(orders.findById(delivered.id())).thenReturn(Optional.of(delivered));
    when(refunds.alreadyRefundedPaise(delivered.id())).thenReturn(0L);
    when(refunds.remainingRefundablePaise(delivered)).thenReturn(100L);
    when(refunds.recommendRefundTo(delivered)).thenReturn(RefundTo.WALLET);
    Map<String, Object> elig = service.refundEligibility(admin, delivered.id());
    assertThat(elig.get("cancellation_eligible")).isEqualTo(false);
    @SuppressWarnings("unchecked")
    Map<String, Object> rec = (Map<String, Object>) elig.get("recommendation");
    assertThat(rec.get("message")).asString().contains("COD");

    Order cancelledElig =
        order(OrderStatus.CANCELLED, PaymentMethod.WALLET, PaymentStatus.REFUNDED, 100);
    when(orders.findById(cancelledElig.id())).thenReturn(Optional.of(cancelledElig));
    when(refunds.alreadyRefundedPaise(cancelledElig.id())).thenReturn(0L);
    when(refunds.remainingRefundablePaise(cancelledElig)).thenReturn(0L);
    when(refunds.recommendRefundTo(cancelledElig)).thenReturn(RefundTo.WALLET);
    Map<String, Object> elig2 = service.refundEligibility(admin, cancelledElig.id());
    assertThat(elig2.get("cancellation_reason")).asString().contains("CANCELLED");
    @SuppressWarnings("unchecked")
    Map<String, Object> rec2 = (Map<String, Object>) elig2.get("recommendation");
    assertThat(rec2.get("message")).asString().contains("wallet");

    Order packingElig = order(OrderStatus.PACKING, PaymentMethod.UPI, PaymentStatus.PAID, 100);
    when(orders.findById(packingElig.id())).thenReturn(Optional.of(packingElig));
    when(refunds.alreadyRefundedPaise(packingElig.id())).thenReturn(0L);
    when(refunds.remainingRefundablePaise(packingElig)).thenReturn(100L);
    when(refunds.recommendRefundTo(packingElig)).thenReturn(RefundTo.SOURCE);
    assertThat(service.refundEligibility(admin, packingElig.id()).get("cancellation_reason"))
        .asString()
        .contains("admin cancellation only");

    assertThatThrownBy(() -> service.customerCancel(customer, UUID.randomUUID(), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.adminCancel(admin, accepted.id(), "r", null, "SOURCE"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(orders.findById(accepted.id())).thenReturn(Optional.of(accepted));
    assertThatThrownBy(() -> service.adminCancel(admin, accepted.id(), "r", "nope", "SOURCE"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    UUID missing = UUID.randomUUID();
    when(orders.findByCustomerAndId(CUST, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.customerCancel(customer, missing, "CHANGED_MIND"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ORDER_NOT_FOUND");
  }

  private Order order(
      OrderStatus status, PaymentMethod method, PaymentStatus payStatus, long payable) {
    return new Order(
        UUID.randomUUID(),
        "ORD-X",
        CUST,
        UUID.randomUUID(),
        UUID.randomUUID(),
        List.of(),
        payable,
        null,
        0,
        2500,
        500,
        0,
        payable,
        method,
        payStatus,
        "rz",
        "pay_1",
        null,
        UUID.randomUUID(),
        null,
        status,
        null,
        null,
        null,
        T0,
        T0,
        T0,
        T0);
  }
}

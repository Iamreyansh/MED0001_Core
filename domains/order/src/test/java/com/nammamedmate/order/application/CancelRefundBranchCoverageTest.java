package com.nammamedmate.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.order.adapter.out.client.StubRazorpayPaymentPort;
import com.nammamedmate.order.application.port.out.OrderCancellationStore;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.application.port.out.RefundStore;
import com.nammamedmate.order.application.port.out.WalletPort;
import com.nammamedmate.order.domain.ActorType;
import com.nammamedmate.order.domain.CancelledByType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Branch-focused coverage for cancel/refund leftovers. */
class CancelRefundBranchCoverageTest {

  private static final Instant T0 = Instant.parse("2026-08-08T06:00:00Z");

  private RefundStore refundStore;
  private OrderCancellationStore cancellations;
  private OrderStore orders;
  private WalletPort wallet;
  private RefundService refunds;
  private final List<Refund> saved = new ArrayList<>();

  @BeforeEach
  void setUp() {
    refundStore = mock(RefundStore.class);
    cancellations = mock(OrderCancellationStore.class);
    orders = mock(OrderStore.class);
    wallet = mock(WalletPort.class);
    when(cancellations.findByOrderId(any())).thenReturn(Optional.empty());
    when(wallet.creditForRefund(any(), any(), anyLong(), any(), any()))
        .thenReturn(UUID.randomUUID());
    when(refundStore.sumSuccessfulPaise(any()))
        .thenAnswer(
            inv ->
                saved.stream()
                    .filter(r -> r.orderId().equals(inv.getArgument(0)))
                    .filter(
                        r ->
                            r.status() == RefundStatus.PENDING
                                || r.status() == RefundStatus.INITIATED
                                || r.status() == RefundStatus.PROCESSED)
                    .mapToLong(Refund::amountPaise)
                    .sum());
    when(refundStore.listByOrderId(any()))
        .thenAnswer(
            inv -> saved.stream().filter(r -> r.orderId().equals(inv.getArgument(0))).toList());
    org.mockito.Mockito.doAnswer(
            inv -> {
              saved.add(inv.getArgument(0));
              return null;
            })
        .when(refundStore)
        .insert(any());
    refunds =
        new RefundService(
            refundStore,
            cancellations,
            orders,
            new StubRazorpayPaymentPort(),
            wallet,
            Clock.fixed(T0, ZoneOffset.UTC));
  }

  @Test
  void refundServiceBranchMatrix() throws Exception {
    // COD collected auto-refund to wallet
    Order codCollected = order(PaymentMethod.COD, PaymentStatus.COLLECTED, 1000, 0, null);
    when(orders.findById(codCollected.id())).thenReturn(Optional.of(codCollected));
    assertThat(refunds.initiate(codCollected, "T", ActorType.SYSTEM, null).initiated()).isTrue();

    // PARTIALLY_REFUNDED / REFUNDED auto due
    Order partial = order(PaymentMethod.UPI, PaymentStatus.PARTIALLY_REFUNDED, 500, 0, "pay_p");
    when(orders.findById(partial.id())).thenReturn(Optional.of(partial));
    assertThat(refunds.initiate(partial, "T", ActorType.SYSTEM, null).initiated()).isTrue();

    Order refunded = order(PaymentMethod.UPI, PaymentStatus.REFUNDED, 0, 100, null);
    when(orders.findById(refunded.id())).thenReturn(Optional.of(refunded));
    assertThat(refunds.initiate(refunded, "T", ActorType.SYSTEM, null).initiated()).isTrue();

    // blank reason / blank notes / truncate long
    refunds.persistCancellation(
        order(PaymentMethod.UPI, PaymentStatus.PAID, 1, 0, "p"),
        "   ",
        CancelledByType.SYSTEM,
        null,
        T0);
    refunds.persistCancellation(
        order(PaymentMethod.UPI, PaymentStatus.PAID, 1, 0, "p2"),
        null,
        CancelledByType.SYSTEM,
        null,
        T0);

    Order upi = order(PaymentMethod.UPI, PaymentStatus.PAID, 2000, 0, "pay_long");
    when(orders.findById(upi.id())).thenReturn(Optional.of(upi));
    refunds.issueManual(
        upi, 100, RefundTo.WALLET, "r".repeat(400), "n".repeat(600), UUID.randomUUID(), "long-1");

    // sourceRefunded filters FAILED
    Refund failed =
        new Refund(
            UUID.randomUUID(),
            upi.id(),
            500,
            RefundTo.SOURCE,
            "f",
            null,
            RefundStatus.FAILED,
            null,
            RefundIssuedByType.SYSTEM,
            "rfnd_f",
            null,
            T0,
            "x",
            null,
            T0);
    saved.add(failed);
    refunds.issueManual(upi, 100, RefundTo.SOURCE, "ok", "notes", UUID.randomUUID(), "src-ok");

    // webhook text branches
    ObjectMapper om = new ObjectMapper();
    assertThat(
            refunds
                .handleRefundProcessed(
                    om.readTree("{\"payload\":{\"refund\":{\"entity\":{\"id\":null}}}}"))
                .get("ignored"))
        .isEqualTo(true);
    assertThat(
            refunds
                .handleRefundProcessed(
                    om.readTree("{\"payload\":{\"refund\":{\"entity\":{\"id\":\"\"}}}}"))
                .get("ignored"))
        .isEqualTo(true);
    when(refundStore.findByRazorpayRefundId("rfnd_miss")).thenReturn(Optional.empty());
    assertThat(
            refunds
                .handleRefundProcessed(
                    om.readTree("{\"payload\":{\"refund\":{\"entity\":{\"id\":\"rfnd_miss\"}}}}"))
                .get("ignored"))
        .isEqualTo(true);

    assertThatThrownBy(() -> RefundService.parseRefundTo(" "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> refunds.issueManual(upi, 1, RefundTo.WALLET, "r", null, UUID.randomUUID(), "   "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThat(
            RefundService.defaultRefundTo(
                order(PaymentMethod.WALLET, PaymentStatus.PAID, 0, 1, null)))
        .isEqualTo(RefundTo.WALLET);

    assertThatThrownBy(() -> new StubRazorpayPaymentPort().refund("  ", 10))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // reverseWallet with null issuedBy → SYSTEM
    Order withWallet = order(PaymentMethod.UPI, PaymentStatus.PAID, 100, 50, "pay_w");
    when(orders.findById(withWallet.id())).thenReturn(Optional.of(withWallet));
    assertThat(refunds.reverseWalletApplied(withWallet, "x", null, T0).issuedByType())
        .isEqualTo(RefundIssuedByType.SYSTEM);

    // COD unpaid + wallet + online: auto-due via wallet; online not payable-refundable
    Order codWallet = order(PaymentMethod.COD, PaymentStatus.PENDING_COLLECTION, 900, 100, null);
    when(orders.findById(codWallet.id())).thenReturn(Optional.of(codWallet));
    assertThat(refunds.initiate(codWallet, "x", ActorType.SYSTEM, null).refundTo())
        .isEqualTo("WALLET");

    // COD collected admin cancel refund + WALLET destination
    Order codPaid = order(PaymentMethod.COD, PaymentStatus.COLLECTED, 500, 0, null);
    when(orders.findById(codPaid.id())).thenReturn(Optional.of(codPaid));
    assertThat(
            refunds.issueOnAdminCancel(codPaid, 100, RefundTo.WALLET, null, UUID.randomUUID(), T0))
        .isNotNull();

    // blank razorpay payment id
    Order blankPay = order(PaymentMethod.UPI, PaymentStatus.PAID, 100, 0, "  ");
    assertThatThrownBy(
            () ->
                refunds.issueManual(
                    blankPay, 50, RefundTo.SOURCE, null, "notes", UUID.randomUUID(), "blank-pay"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // PROCESSED source counts toward sourceRefunded
    Order upi2 = order(PaymentMethod.UPI, PaymentStatus.PAID, 1000, 0, "pay_2");
    when(orders.findById(upi2.id())).thenReturn(Optional.of(upi2));
    Refund processedSrc =
        new Refund(
            UUID.randomUUID(),
            upi2.id(),
            400,
            RefundTo.SOURCE,
            "p",
            null,
            RefundStatus.PROCESSED,
            null,
            RefundIssuedByType.SYSTEM,
            "rfnd_p",
            null,
            T0,
            null,
            null,
            T0);
    saved.add(processedSrc);
    refunds.issueManual(upi2, 100, RefundTo.SOURCE, "short", "ok", UUID.randomUUID(), "src-p");

    // isAutoRefundDue via REFUNDED status (no wallet)
    Order alreadyRefunded = order(PaymentMethod.UPI, PaymentStatus.REFUNDED, 500, 0, "pay_r");
    when(orders.findById(alreadyRefunded.id())).thenReturn(Optional.of(alreadyRefunded));
    assertThat(refunds.initiate(alreadyRefunded, "x", ActorType.SYSTEM, null).initiated())
        .isFalse();

    // isAutoRefundDue via walletApplied on non-COD non-paid
    Order awaitingWithWallet =
        order(PaymentMethod.UPI, PaymentStatus.AWAITING_PAYMENT, 0, 150, null);
    when(orders.findById(awaitingWithWallet.id())).thenReturn(Optional.of(awaitingWithWallet));
    assertThat(refunds.initiate(awaitingWithWallet, "x", ActorType.SYSTEM, null).initiated())
        .isTrue();

    // isPayableRefundable PARTIALLY_REFUNDED
    Order partialPay = order(PaymentMethod.UPI, PaymentStatus.PARTIALLY_REFUNDED, 300, 0, "pay_pr");
    when(orders.findById(partialPay.id())).thenReturn(Optional.of(partialPay));
    assertThat(refunds.initiate(partialPay, "x", ActorType.SYSTEM, null).initiated()).isTrue();

    // INITIATED source refunds count in sourceRefundedPaise
    Order upi3 = order(PaymentMethod.UPI, PaymentStatus.PAID, 1000, 0, "pay_3");
    when(orders.findById(upi3.id())).thenReturn(Optional.of(upi3));
    saved.add(
        new Refund(
            UUID.randomUUID(),
            upi3.id(),
            200,
            RefundTo.SOURCE,
            "i",
            null,
            RefundStatus.INITIATED,
            null,
            RefundIssuedByType.SYSTEM,
            "rfnd_i",
            null,
            null,
            null,
            null,
            T0));
    refunds.issueManual(upi3, 50, RefundTo.SOURCE, "ok", "notes", UUID.randomUUID(), "src-i");

    // isAutoRefundDue false path evaluates walletApplied==0 on non-COD
    Order awaitingBare = order(PaymentMethod.UPI, PaymentStatus.AWAITING_PAYMENT, 100, 0, null);
    assertThat(refunds.initiate(awaitingBare, "x", ActorType.SYSTEM, null).initiated()).isFalse();

    // truncate short branch via notes
    Order upi4 = order(PaymentMethod.UPI, PaymentStatus.PAID, 200, 0, "pay_4");
    when(orders.findById(upi4.id())).thenReturn(Optional.of(upi4));
    refunds.issueManual(
        upi4, 10, RefundTo.WALLET, "r", "short-notes", UUID.randomUUID(), "notes-short");
  }

  @Test
  void cancellationAuthAndReasonBranches() {
    OrderStore orderStore = mock(OrderStore.class);
    var events = mock(com.nammamedmate.order.application.port.out.OrderStatusEventStore.class);
    RefundService refundMock = mock(RefundService.class);
    var rl = mock(com.nammamedmate.kernel.ratelimit.RateLimiter.class);
    when(rl.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    OrderCancellationService svc =
        new OrderCancellationService(
            orderStore,
            events,
            refundMock,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            rl,
            Clock.fixed(T0, ZoneOffset.UTC));

    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    MedmatePrincipal finance =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    MedmatePrincipal ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");

    assertThatThrownBy(() -> svc.customerCancel(finance, UUID.randomUUID(), "CHANGED_MIND"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> svc.adminCancel(null, UUID.randomUUID(), "r", 1, "SOURCE"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> svc.adminCancel(finance, UUID.randomUUID(), "r", 1, "SOURCE"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> svc.adminRefund(null, UUID.randomUUID(), 1, "WALLET", "r", null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> svc.adminCancel(ops, UUID.randomUUID(), "  ", 1, "SOURCE"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> svc.adminRefund(ops, UUID.randomUUID(), 1, "WALLET", "  ", null, "k"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> svc.customerCancel(customer, UUID.randomUUID(), "  "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    MedmatePrincipal superAdmin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    Order codCollected =
        new Order(
            UUID.randomUUID(),
            "COD",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            List.of(),
            1000,
            null,
            0,
            0,
            0,
            0,
            1000,
            PaymentMethod.COD,
            PaymentStatus.COLLECTED,
            null,
            null,
            null,
            UUID.randomUUID(),
            null,
            OrderStatus.ACCEPTED,
            null,
            null,
            null,
            T0,
            T0,
            T0,
            T0);
    when(orderStore.findById(codCollected.id())).thenReturn(Optional.of(codCollected));
    when(refundMock.issueOnAdminCancel(any(), anyLong(), any(), any(), any(), any()))
        .thenReturn(
            new Refund(
                UUID.randomUUID(),
                codCollected.id(),
                1000,
                RefundTo.WALLET,
                "r",
                null,
                RefundStatus.PROCESSED,
                superAdmin.subject(),
                RefundIssuedByType.ADMIN,
                null,
                UUID.randomUUID(),
                T0,
                null,
                null,
                T0));
    Map<String, Object> codCancel =
        svc.adminCancel(superAdmin, codCollected.id(), "cod collected", 10.00, "WALLET");
    assertThat(((Map<?, ?>) codCancel.get("refund")).get("initiated")).isEqualTo(true);

    when(orderStore.findById(codCollected.id())).thenReturn(Optional.of(codCollected));
    when(refundMock.issueManual(any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(
            new Refund(
                UUID.randomUUID(),
                codCollected.id(),
                100,
                RefundTo.WALLET,
                "r",
                null,
                RefundStatus.PROCESSED,
                superAdmin.subject(),
                RefundIssuedByType.ADMIN,
                null,
                UUID.randomUUID(),
                T0,
                null,
                "k2",
                T0));
    svc.adminRefund(
        superAdmin, codCollected.id(), 1.00, "WALLET", "reason", null, "idem-null-notes");
    svc.adminRefund(finance, codCollected.id(), 1.00, "WALLET", "reason", "ok", "idem-fin");

    Order upiZero =
        new Order(
            UUID.randomUUID(),
            "U0",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            List.of(),
            1000,
            null,
            0,
            0,
            0,
            0,
            1000,
            PaymentMethod.UPI,
            PaymentStatus.PAID,
            "rz",
            "pay",
            null,
            UUID.randomUUID(),
            null,
            OrderStatus.ACCEPTED,
            null,
            null,
            null,
            T0,
            T0,
            T0,
            T0);
    when(orderStore.findById(upiZero.id())).thenReturn(Optional.of(upiZero));
    Map<String, Object> zeroRefundCancel =
        svc.adminCancel(ops, upiZero.id(), "zero refund amount", 0, "SOURCE");
    assertThat(((Map<?, ?>) zeroRefundCancel.get("refund")).get("initiated")).isEqualTo(false);

    when(orderStore.findById(upiZero.id())).thenReturn(Optional.of(upiZero));
    when(refundMock.issueManual(any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(
            new Refund(
                UUID.randomUUID(),
                upiZero.id(),
                100,
                RefundTo.WALLET,
                "r",
                null,
                RefundStatus.PROCESSED,
                ops.subject(),
                RefundIssuedByType.ADMIN,
                null,
                UUID.randomUUID(),
                T0,
                null,
                "k3",
                T0));
    svc.adminRefund(ops, upiZero.id(), 1.00, "WALLET", "reason", "under-limit", "idem-notes-ok");

    when(orderStore.findById(upiZero.id())).thenReturn(Optional.of(upiZero));
    when(refundMock.issueManual(any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(
            new Refund(
                UUID.randomUUID(),
                upiZero.id(),
                100,
                RefundTo.SOURCE,
                "r",
                null,
                RefundStatus.INITIATED,
                ops.subject(),
                RefundIssuedByType.ADMIN,
                "rfnd_open",
                null,
                null,
                null,
                "k4",
                T0));
    Map<String, Object> initiatedRefund =
        svc.adminRefund(ops, upiZero.id(), 1.00, "SOURCE", "reason", null, "idem-initiated");
    assertThat(initiatedRefund.get("processed_at")).isNull();

    Order pending = order(PaymentMethod.UPI, PaymentStatus.PAID, 100, 0, "p");
    // force status PENDING_ACCEPTANCE
    pending =
        new Order(
            pending.id(),
            pending.orderNumber(),
            pending.customerId(),
            pending.pharmacyId(),
            pending.cartId(),
            List.of(),
            100,
            null,
            0,
            0,
            0,
            0,
            100,
            PaymentMethod.UPI,
            PaymentStatus.PAID,
            "rz",
            "p",
            null,
            UUID.randomUUID(),
            null,
            OrderStatus.PENDING_ACCEPTANCE,
            null,
            null,
            null,
            T0,
            T0,
            T0,
            T0);
    when(orderStore.findById(pending.id())).thenReturn(Optional.of(pending));
    when(refundMock.alreadyRefundedPaise(pending.id())).thenReturn(0L);
    when(refundMock.remainingRefundablePaise(pending)).thenReturn(100L);
    when(refundMock.recommendRefundTo(pending)).thenReturn(RefundTo.SOURCE);
    assertThat(svc.refundEligibility(ops, pending.id()).get("cancellation_reason"))
        .asString()
        .contains("PENDING_ACCEPTANCE");

    // admin cancel with walletApplied and notes blank on refund
    Order withWallet =
        new Order(
            UUID.randomUUID(),
            "O",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            List.of(),
            1500,
            null,
            0,
            0,
            0,
            500,
            1000,
            PaymentMethod.UPI,
            PaymentStatus.PAID,
            "rz",
            "pay",
            null,
            UUID.randomUUID(),
            null,
            OrderStatus.ACCEPTED,
            null,
            null,
            null,
            T0,
            T0,
            T0,
            T0);
    when(orderStore.findById(withWallet.id())).thenReturn(Optional.of(withWallet));
    when(refundMock.issueOnAdminCancel(any(), anyLong(), any(), any(), any(), any()))
        .thenReturn(
            new Refund(
                UUID.randomUUID(),
                withWallet.id(),
                1000,
                RefundTo.SOURCE,
                "r",
                null,
                RefundStatus.INITIATED,
                ops.subject(),
                RefundIssuedByType.ADMIN,
                "rfnd",
                null,
                null,
                null,
                null,
                T0));
    svc.adminCancel(ops, withWallet.id(), "ops", 10.00, "SOURCE");
    verify(refundMock).reverseWalletApplied(any(), anyString(), any(), any());

    when(orderStore.findById(withWallet.id())).thenReturn(Optional.of(withWallet));
    when(refundMock.issueManual(any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(
            new Refund(
                UUID.randomUUID(),
                withWallet.id(),
                100,
                RefundTo.WALLET,
                "r",
                null,
                RefundStatus.PROCESSED,
                ops.subject(),
                RefundIssuedByType.ADMIN,
                null,
                UUID.randomUUID(),
                T0,
                null,
                "k",
                T0));
    svc.adminRefund(ops, withWallet.id(), 1.00, "WALLET", "reason", "  ", "idem-blank-notes");
  }

  private static Order order(
      PaymentMethod method, PaymentStatus status, long payable, long walletApplied, String payId) {
    return new Order(
        UUID.randomUUID(),
        "ORD",
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        List.of(),
        payable + walletApplied,
        null,
        0,
        0,
        0,
        walletApplied,
        payable,
        method,
        status,
        "rz",
        payId,
        null,
        UUID.randomUUID(),
        null,
        OrderStatus.ACCEPTED,
        null,
        null,
        null,
        T0,
        T0,
        T0,
        T0);
  }
}

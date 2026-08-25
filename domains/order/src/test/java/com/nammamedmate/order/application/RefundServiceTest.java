package com.nammamedmate.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.order.application.port.out.OrderCancellationStore;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.application.port.out.RazorpayPaymentPort;
import com.nammamedmate.order.application.port.out.RefundStore;
import com.nammamedmate.order.application.port.out.WalletPort;
import com.nammamedmate.order.domain.ActorType;
import com.nammamedmate.order.domain.CancelledByType;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderCancellation;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.PaymentMethod;
import com.nammamedmate.order.domain.PaymentStatus;
import com.nammamedmate.order.domain.Refund;
import com.nammamedmate.order.domain.RefundIssuedByType;
import com.nammamedmate.order.domain.RefundStatus;
import com.nammamedmate.order.domain.RefundTo;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RefundServiceTest {

  private static final Instant T0 = Instant.parse("2026-08-08T06:00:00Z");

  private RefundStore refundStore;
  private OrderCancellationStore cancellationStore;
  private OrderStore orderStore;
  private RazorpayPaymentPort razorpay;
  private WalletPort wallet;
  private RefundService service;
  private final List<Refund> saved = new ArrayList<>();

  @BeforeEach
  void setUp() {
    refundStore = mock(RefundStore.class);
    cancellationStore = mock(OrderCancellationStore.class);
    orderStore = mock(OrderStore.class);
    razorpay = mock(RazorpayPaymentPort.class);
    wallet = mock(WalletPort.class);
    service =
        new RefundService(
            refundStore,
            cancellationStore,
            orderStore,
            razorpay,
            wallet,
            Clock.fixed(T0, ZoneOffset.UTC));
    when(cancellationStore.findByOrderId(any())).thenReturn(Optional.empty());
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
    org.mockito.Mockito.doAnswer(
            inv -> {
              Refund r = inv.getArgument(0);
              saved.removeIf(x -> x.id().equals(r.id()));
              saved.add(r);
              return null;
            })
        .when(refundStore)
        .update(any());
    when(wallet.creditForRefund(any(), any(), anyLong(), any(), any()))
        .thenReturn(UUID.randomUUID());
    when(razorpay.refund(anyString(), anyLong()))
        .thenAnswer(
            inv ->
                new RazorpayPaymentPort.RefundResult(
                    "rfnd_" + inv.getArgument(0), inv.getArgument(1)));
  }

  @Test
  void initiate_codUnpaid_noRefund() {
    Order cod = order(PaymentMethod.COD, PaymentStatus.PENDING_COLLECTION, 22125, 0, null);
    var plan = service.initiate(cod, "TIMEOUT", ActorType.SYSTEM, null);
    assertThat(plan.initiated()).isFalse();
    verify(cancellationStore).insert(any());
    verify(razorpay, never()).refund(any(), anyLong());
  }

  @Test
  void initiate_upi_customerCancel_queuesPending() {
    Order upi = order(PaymentMethod.UPI, PaymentStatus.PAID, 22125, 0, "pay_1");
    when(orderStore.findById(upi.id())).thenReturn(Optional.of(upi));
    var plan = service.initiate(upi, "CHANGED_MIND", ActorType.CUSTOMER, upi.customerId());
    assertThat(plan.initiated()).isTrue();
    assertThat(plan.refundTo()).isEqualTo("SOURCE");
    assertThat(plan.amountPaise()).isEqualTo(22125);
    assertThat(saved).hasSize(1);
    assertThat(saved.getFirst().status()).isEqualTo(RefundStatus.PENDING);
    verify(razorpay, never()).refund(any(), anyLong());
  }

  @Test
  void ac001_pharmacyUpiUnderThreshold_autoProcesses() {
    Order upi = order(PaymentMethod.UPI, PaymentStatus.PAID, 45000, 0, "pay_1");
    when(orderStore.findById(upi.id())).thenReturn(Optional.of(upi));
    var plan = service.initiate(upi, "PHARMACY_CANCELLED", ActorType.PHARMACY, UUID.randomUUID());
    assertThat(plan.initiated()).isTrue();
    assertThat(saved.getFirst().status()).isEqualTo(RefundStatus.INITIATED);
    assertThat(saved.getFirst().autoProcessed()).isTrue();
    verify(razorpay).refund("pay_1", 45000L);
  }

  @Test
  void ac002_pharmacyUpiOverThreshold_staysPending() {
    Order upi = order(PaymentMethod.UPI, PaymentStatus.PAID, 60_000, 0, "pay_1");
    when(orderStore.findById(upi.id())).thenReturn(Optional.of(upi));
    var plan = service.initiate(upi, "PHARMACY_CANCELLED", ActorType.PHARMACY, UUID.randomUUID());
    assertThat(plan.initiated()).isTrue();
    assertThat(saved.getFirst().status()).isEqualTo(RefundStatus.PENDING);
    assertThat(saved.getFirst().autoProcessed()).isFalse();
    verify(razorpay, never()).refund(any(), anyLong());
  }

  @Test
  void initiate_walletOnly_creditsWallet() {
    Order walletOrder = order(PaymentMethod.WALLET, PaymentStatus.PAID, 0, 22125, null);
    when(orderStore.findById(walletOrder.id())).thenReturn(Optional.of(walletOrder));
    var plan = service.initiate(walletOrder, "X", ActorType.SYSTEM, null);
    assertThat(plan.initiated()).isTrue();
    assertThat(plan.refundTo()).isEqualTo("WALLET");
    verify(wallet)
        .creditForRefund(
            eq(walletOrder.customerId()), eq(walletOrder.id()), eq(22125L), any(), any());
  }

  @Test
  void initiate_split_walletAndSource() {
    Order split = order(PaymentMethod.UPI, PaymentStatus.PAID, 15000, 5000, "pay_s");
    when(orderStore.findById(split.id())).thenReturn(Optional.of(split));
    var plan = service.initiate(split, "TIMEOUT", ActorType.SYSTEM, null);
    assertThat(plan.initiated()).isTrue();
    assertThat(saved).hasSize(2);
    assertThat(plan.refundTo()).isEqualTo("SOURCE");
  }

  @Test
  void initiate_skipsDuplicateCancellation() {
    Order upi = order(PaymentMethod.UPI, PaymentStatus.PAID, 100, 0, "pay_1");
    when(cancellationStore.findByOrderId(upi.id()))
        .thenReturn(
            Optional.of(
                new OrderCancellation(
                    UUID.randomUUID(), upi.id(), CancelledByType.SYSTEM, null, "x", T0)));
    when(orderStore.findById(upi.id())).thenReturn(Optional.of(upi));
    service.initiate(upi, "r", ActorType.PHARMACY, UUID.randomUUID());
    verify(cancellationStore, never()).insert(any());
  }

  @Test
  void issueManual_idempotentAndExceeds() {
    Order upi = order(PaymentMethod.UPI, PaymentStatus.PAID, 22125, 0, "pay_1");
    when(orderStore.findById(upi.id())).thenReturn(Optional.of(upi));
    Refund first =
        service.issueManual(
            upi, 5000, RefundTo.WALLET, "partial", "n", UUID.randomUUID(), "idem-1");
    assertThat(first.status()).isEqualTo(RefundStatus.PROCESSED);
    when(refundStore.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(first));
    assertThat(
            service.issueManual(
                upi, 5000, RefundTo.WALLET, "partial", "n", UUID.randomUUID(), "idem-1"))
        .isSameAs(first);

    assertThatThrownBy(
            () ->
                service.issueManual(
                    upi, 30000, RefundTo.WALLET, "big", null, UUID.randomUUID(), "idem-2"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFUND_EXCEEDS_REMAINING_REFUNDABLE");

    assertThatThrownBy(
            () ->
                service.issueManual(
                    upi, 22125, RefundTo.SOURCE, "s", null, UUID.randomUUID(), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.issueManual(
                    upi, 100, RefundTo.SOURCE, "partial", null, UUID.randomUUID(), "need-notes"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.issueManual(
                    upi, 100, RefundTo.SOURCE, "partial", "  ", UUID.randomUUID(), "blank-notes"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // full SOURCE with blank notes normalizes to null notes
    Order full = order(PaymentMethod.UPI, PaymentStatus.PAID, 5000, 0, "pay_full");
    when(orderStore.findById(full.id())).thenReturn(Optional.of(full));
    Refund blankNotes =
        service.issueManual(
            full, 5000, RefundTo.SOURCE, "full", "  ", UUID.randomUUID(), "full-blank");
    assertThat(blankNotes.notes()).isNull();
  }

  @Test
  void issueManual_sourceCapAndMissingPayment() {
    Order upi = order(PaymentMethod.UPI, PaymentStatus.PAID, 1000, 0, "pay_1");
    when(orderStore.findById(upi.id())).thenReturn(Optional.of(upi));
    service.issueManual(upi, 1000, RefundTo.SOURCE, "full", null, UUID.randomUUID(), "s1");
    assertThatThrownBy(
            () ->
                service.issueManual(upi, 1, RefundTo.SOURCE, "over", null, UUID.randomUUID(), "s2"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFUND_EXCEEDS_REMAINING_REFUNDABLE");

    Order noPay = order(PaymentMethod.UPI, PaymentStatus.PAID, 500, 0, null);
    assertThatThrownBy(
            () ->
                service.issueManual(
                    noPay, 100, RefundTo.SOURCE, "x", "notes", UUID.randomUUID(), "s3"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void issueOnAdminCancel_codAndExceeds() {
    Order cod = order(PaymentMethod.COD, PaymentStatus.PENDING_COLLECTION, 22125, 0, null);
    assertThat(service.issueOnAdminCancel(cod, 100, RefundTo.WALLET, "r", UUID.randomUUID(), T0))
        .isNull();
    assertThat(service.issueOnAdminCancel(cod, 0, RefundTo.WALLET, "r", UUID.randomUUID(), T0))
        .isNull();

    Order upi = order(PaymentMethod.UPI, PaymentStatus.PAID, 1000, 0, "pay_1");
    assertThatThrownBy(
            () ->
                service.issueOnAdminCancel(upi, 2000, RefundTo.SOURCE, "r", UUID.randomUUID(), T0))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFUND_EXCEEDS_ORDER_TOTAL");
  }

  @Test
  void reverseWalletAndWebhook() throws Exception {
    Order upi = order(PaymentMethod.UPI, PaymentStatus.PAID, 1000, 500, "pay_1");
    when(orderStore.findById(upi.id())).thenReturn(Optional.of(upi));
    Refund rev = service.reverseWalletApplied(upi, "cancel", UUID.randomUUID(), T0);
    assertThat(rev.refundTo()).isEqualTo(RefundTo.WALLET);
    assertThat(
            service.reverseWalletApplied(
                order(PaymentMethod.UPI, PaymentStatus.PAID, 100, 0, "p"), "x", null, T0))
        .isNull();

    Refund source =
        new Refund(
            UUID.randomUUID(),
            upi.id(),
            1000,
            RefundTo.SOURCE,
            "r",
            null,
            RefundStatus.INITIATED,
            null,
            RefundIssuedByType.SYSTEM,
            "rfnd_done",
            null,
            null,
            null,
            null,
            T0);
    when(refundStore.findByRazorpayRefundId("rfnd_done")).thenReturn(Optional.of(source));
    ObjectMapper om = new ObjectMapper();
    var root = om.readTree("{\"payload\":{\"refund\":{\"entity\":{\"id\":\"rfnd_done\"}}}}");
    assertThat(service.handleRefundProcessed(root).get("status")).isEqualTo("PROCESSED");
    source.markProcessed(T0);
    assertThat(service.handleRefundProcessed(root).get("status")).isEqualTo("PROCESSED");
    assertThat(
            service
                .handleRefundProcessed(om.readTree("{\"payload\":{\"refund\":{\"entity\":{}}}}"))
                .get("ignored"))
        .isEqualTo(true);
    when(refundStore.findByRazorpayRefundId("missing")).thenReturn(Optional.empty());
    assertThat(
            service
                .handleRefundProcessed(
                    om.readTree("{\"payload\":{\"refund\":{\"entity\":{\"id\":\"missing\"}}}}"))
                .get("ignored"))
        .isEqualTo(true);
  }

  @Test
  void markFailedAndLongReason() {
    Refund r =
        new Refund(
            UUID.randomUUID(),
            UUID.randomUUID(),
            100,
            RefundTo.WALLET,
            "r",
            null,
            RefundStatus.INITIATED,
            null,
            RefundIssuedByType.SYSTEM,
            null,
            null,
            null,
            null,
            null,
            T0);
    r.markFailed("boom", T0);
    assertThat(r.status()).isEqualTo(RefundStatus.FAILED);
    assertThat(r.failedReason()).isEqualTo("boom");
    Order upi = order(PaymentMethod.UPI, PaymentStatus.PAID, 100, 0, "pay_1");
    when(cancellationStore.findByOrderId(upi.id())).thenReturn(Optional.empty());
    when(orderStore.findById(upi.id())).thenReturn(Optional.of(upi));
    service.persistCancellation(upi, "x".repeat(400), CancelledByType.ADMIN, null, T0);
    verify(cancellationStore).insert(any());
  }

  @Test
  void helpersAndRemainingGaps() {
    Order upi = order(PaymentMethod.UPI, PaymentStatus.PAID, 100, 0, "p");
    assertThat(RefundService.onlinePortionMax(upi)).isEqualTo(100);
    assertThat(
            RefundService.onlinePortionMax(
                order(PaymentMethod.COD, PaymentStatus.PENDING_COLLECTION, 100, 0, null)))
        .isZero();
    assertThat(RefundService.parseRefundTo("wallet")).isEqualTo(RefundTo.WALLET);
    assertThatThrownBy(() -> RefundService.parseRefundTo(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> RefundService.parseRefundTo("X"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(service.recommendRefundTo(upi)).isEqualTo(RefundTo.SOURCE);
    assertThat(service.remainingRefundablePaise(upi)).isEqualTo(100);
    assertThat(service.alreadyRefundedPaise(upi.id())).isZero();

    assertThatThrownBy(
            () ->
                service.issueManual(
                    upi, 0, RefundTo.WALLET, "z", null, UUID.randomUUID(), "idem-z"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.issueManual(
                    upi, 10, RefundTo.WALLET, "z", null, UUID.randomUUID(), "k".repeat(129)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    Order upiSource = order(PaymentMethod.UPI, PaymentStatus.PAID, 1000, 0, "pay_1");
    when(orderStore.findById(upiSource.id())).thenReturn(Optional.of(upiSource));
    service.issueManual(
        upiSource, 500, RefundTo.SOURCE, "a", "partial notes", UUID.randomUUID(), "src-a");
    assertThatThrownBy(
            () ->
                service.issueManual(
                    upiSource, 600, RefundTo.SOURCE, "b", "notes", UUID.randomUUID(), "src-b"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFUND_EXCEEDS_REMAINING_REFUNDABLE");

    Order upiCap = order(PaymentMethod.UPI, PaymentStatus.PAID, 1000, 0, "pay_1");
    when(orderStore.findById(upiCap.id())).thenReturn(Optional.of(upiCap));
    assertThat(service.issueOnAdminCancel(upiCap, 500, RefundTo.SOURCE, "r", UUID.randomUUID(), T0))
        .isNotNull();
    assertThatThrownBy(
            () ->
                service.issueOnAdminCancel(
                    upiCap, 1001, RefundTo.SOURCE, "r", UUID.randomUUID(), T0))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFUND_EXCEEDS_ORDER_TOTAL");
    Order wallet = order(PaymentMethod.WALLET, PaymentStatus.PAID, 1000, 0, null);
    assertThatThrownBy(
            () ->
                service.issueOnAdminCancel(
                    wallet, 100, RefundTo.SOURCE, "r", UUID.randomUUID(), T0))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFUND_EXCEEDS_ORDER_TOTAL");
    assertThatThrownBy(
            () ->
                service.issueManual(
                    wallet, 100, RefundTo.SOURCE, "src", "notes", UUID.randomUUID(), "wal-src"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFUND_EXCEEDS_REMAINING_REFUNDABLE");

    Order zeroPaid = order(PaymentMethod.UPI, PaymentStatus.PAID, 0, 0, "pay_z");
    assertThat(service.initiate(zeroPaid, "x", ActorType.SYSTEM, null).initiated()).isFalse();

    // refresh when order missing after insert
    Order ghost = order(PaymentMethod.UPI, PaymentStatus.PAID, 200, 0, "pay_g");
    when(orderStore.findById(ghost.id())).thenReturn(Optional.empty());
    service.issueManual(ghost, 100, RefundTo.SOURCE, "g", "notes", UUID.randomUUID(), "ghost");

    assertThat(
            service.recommendRefundTo(
                order(PaymentMethod.COD, PaymentStatus.COLLECTED, 1, 0, null)))
        .isEqualTo(RefundTo.WALLET);

    Order paid = order(PaymentMethod.UPI, PaymentStatus.PAID, 100, 0, "pay_x");
    when(orderStore.findById(paid.id())).thenReturn(Optional.of(paid));
    service.initiate(paid, "r", ActorType.ADMIN, UUID.randomUUID());
    Order paid2 = order(PaymentMethod.UPI, PaymentStatus.PAID, 50, 0, "pay_y");
    when(orderStore.findById(paid2.id())).thenReturn(Optional.of(paid2));
    service.initiate(paid2, "r", null, null);

    Refund setters =
        new Refund(
            UUID.randomUUID(),
            paid.id(),
            1,
            RefundTo.SOURCE,
            "r",
            null,
            RefundStatus.INITIATED,
            null,
            RefundIssuedByType.SYSTEM,
            null,
            null,
            null,
            null,
            null,
            T0);
    setters.setRazorpayRefundId("rfnd_set");
    setters.setWalletTransactionId(UUID.randomUUID());
    assertThat(setters.razorpayRefundId()).isEqualTo("rfnd_set");
    assertThat(setters.walletTransactionId()).isNotNull();

    // refresh with sumSuccessful=0 after insert
    when(refundStore.sumSuccessfulPaise(any())).thenReturn(0L);
    Order zeroSum = order(PaymentMethod.UPI, PaymentStatus.PAID, 500, 0, "pay_zs");
    when(orderStore.findById(zeroSum.id())).thenReturn(Optional.of(zeroSum));
    service.issueManual(zeroSum, 100, RefundTo.SOURCE, "zs", "notes", UUID.randomUUID(), "zs-1");

    // PENDING SOURCE counts toward sourceRefundedPaise
    Order pendingCap = order(PaymentMethod.UPI, PaymentStatus.PAID, 10_000, 0, "pay_pc");
    when(orderStore.findById(pendingCap.id())).thenReturn(Optional.of(pendingCap));
    service.initiate(pendingCap, "CHANGED_MIND", ActorType.CUSTOMER, pendingCap.customerId());
    assertThat(saved.getLast().status()).isEqualTo(RefundStatus.PENDING);
    assertThat(saved.getLast().processedBy()).isNull();
    assertThatThrownBy(
            () ->
                service.issueManual(
                    pendingCap, 1, RefundTo.SOURCE, "more", "notes", UUID.randomUUID(), "pc-over"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFUND_EXCEEDS_REMAINING_REFUNDABLE");

    assertThat(RefundService.addBusinessDays(java.time.LocalDate.parse("2026-08-07"), 1)) // Friday
        .isEqualTo(java.time.LocalDate.parse("2026-08-10")); // skips Sat/Sun
  }

  private static Order order(
      PaymentMethod method,
      PaymentStatus status,
      long payable,
      long walletApplied,
      String paymentId) {
    return new Order(
        UUID.randomUUID(),
        "ORD-1",
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        List.of(),
        payable + walletApplied,
        null,
        0,
        2500,
        500,
        walletApplied,
        payable,
        method,
        status,
        "order_rz",
        paymentId,
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
  }

  @Test
  void razorpayFailureAndTxManagerBranches() {
    org.springframework.transaction.PlatformTransactionManager tm =
        mock(org.springframework.transaction.PlatformTransactionManager.class);
    when(tm.getTransaction(any()))
        .thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
    RefundService withTx =
        new RefundService(
            refundStore,
            cancellationStore,
            orderStore,
            razorpay,
            wallet,
            Clock.fixed(T0, ZoneOffset.UTC),
            tm);

    Order ok = order(PaymentMethod.UPI, PaymentStatus.PAID, 45000, 0, "pay_ok");
    when(orderStore.findById(ok.id())).thenReturn(Optional.of(ok));
    when(razorpay.refund(anyString(), anyLong()))
        .thenReturn(new RazorpayPaymentPort.RefundResult("rfnd_ok", 45000L));
    assertThat(
            withTx
                .initiate(ok, "PHARMACY_CANCELLED", ActorType.PHARMACY, UUID.randomUUID())
                .initiated())
        .isTrue();

    Order upi = order(PaymentMethod.UPI, PaymentStatus.PAID, 45000, 0, "pay_fail");
    when(orderStore.findById(upi.id())).thenReturn(Optional.of(upi));
    org.mockito.Mockito.doThrow(new AppException("RAZORPAY_ERROR", "down", 502))
        .when(razorpay)
        .refund(anyString(), anyLong());
    assertThatThrownBy(
            () ->
                service.initiate(upi, "PHARMACY_CANCELLED", ActorType.PHARMACY, UUID.randomUUID()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RAZORPAY_ERROR");
    assertThat(saved.getLast().status()).isEqualTo(RefundStatus.FAILED);

    Order upi2 = order(PaymentMethod.UPI, PaymentStatus.PAID, 45000, 0, "pay_fail2");
    when(orderStore.findById(upi2.id())).thenReturn(Optional.of(upi2));
    org.mockito.Mockito.doThrow(new RuntimeException())
        .when(razorpay)
        .refund(anyString(), anyLong());
    assertThatThrownBy(
            () ->
                service.initiate(upi2, "PHARMACY_CANCELLED", ActorType.PHARMACY, UUID.randomUUID()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RAZORPAY_REFUND_FAILED");

    ObjectMapper om = new ObjectMapper();
    assertThat(RefundService.text(om.createObjectNode(), "id")).isNull();
    com.fasterxml.jackson.databind.JsonNode child =
        mock(com.fasterxml.jackson.databind.JsonNode.class);
    when(child.isNull()).thenReturn(false);
    when(child.asText()).thenReturn(null);
    com.fasterxml.jackson.databind.JsonNode entity =
        mock(com.fasterxml.jackson.databind.JsonNode.class);
    when(entity.get("id")).thenReturn(child);
    assertThat(RefundService.text(entity, "id")).isNull();
  }

  @Test
  void executeRefund_withProviderOps_replaysAndMarksSent() {
    com.nammamedmate.messaging.ProviderOperationStore ops =
        mock(com.nammamedmate.messaging.ProviderOperationStore.class);
    RefundService withOps =
        new RefundService(
            refundStore,
            cancellationStore,
            orderStore,
            razorpay,
            wallet,
            Clock.fixed(T0, ZoneOffset.UTC),
            null,
            ops);
    Order upi = order(PaymentMethod.UPI, PaymentStatus.PAID, 45000, 0, "pay_ops");
    when(orderStore.findById(upi.id())).thenReturn(Optional.of(upi));
    when(ops.find(eq("REFUND"), anyString())).thenReturn(Optional.empty());
    when(razorpay.refund(anyString(), anyLong()))
        .thenReturn(new RazorpayPaymentPort.RefundResult("rfnd_ops", 45000L));
    assertThat(
            withOps
                .initiate(upi, "PHARMACY_CANCELLED", ActorType.PHARMACY, UUID.randomUUID())
                .initiated())
        .isTrue();
    verify(ops).ensurePending(eq("REFUND"), anyString(), eq("razorpay"));
    verify(ops).markSent(eq("REFUND"), anyString(), eq("rfnd_ops"));

    Order replay = order(PaymentMethod.UPI, PaymentStatus.PAID, 45000, 0, "pay_replay");
    when(orderStore.findById(replay.id())).thenReturn(Optional.of(replay));
    when(ops.find(eq("REFUND"), anyString()))
        .thenReturn(
            Optional.of(
                new com.nammamedmate.messaging.ProviderOperationStore.Operation(
                    "REFUND", "order-refund:x", "rfnd_replay", "SENT")));
    assertThat(
            withOps
                .initiate(replay, "PHARMACY_CANCELLED", ActorType.PHARMACY, UUID.randomUUID())
                .initiated())
        .isTrue();
  }
}

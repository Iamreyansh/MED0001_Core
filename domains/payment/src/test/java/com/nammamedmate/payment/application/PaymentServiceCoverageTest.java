package com.nammamedmate.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.adapter.out.client.StubRazorpayGatewayClient;
import com.nammamedmate.payment.application.port.out.FinancialLedgerWriterPort;
import com.nammamedmate.payment.application.port.out.OrderLookupPort;
import com.nammamedmate.payment.application.port.out.OrderLookupPort.OrderSnapshot;
import com.nammamedmate.payment.application.port.out.OrderPaymentStatusPort;
import com.nammamedmate.payment.application.port.out.PaymentStore;
import com.nammamedmate.payment.application.port.out.RazorpayGatewayPort;
import com.nammamedmate.payment.application.port.out.WalletPort;
import com.nammamedmate.payment.domain.Payment;
import com.nammamedmate.payment.domain.PaymentMethod;
import com.nammamedmate.payment.domain.PaymentStatus;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Mock private PaymentStore store;
  @Mock private WalletPort wallet;
  @Mock private OrderLookupPort orders;
  @Mock private OrderPaymentStatusPort orderStatus;
  @Mock private FinancialLedgerWriterPort ledger;
  @Mock private RazorpayGatewayPort razorpayMock;

  private StubRazorpayGatewayClient razorpay;
  private PaymentService service;
  private final UUID customerId = UUID.randomUUID();
  private final UUID orderId = UUID.randomUUID();
  private final MedmatePrincipal customer =
      new MedmatePrincipal(customerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final AtomicReference<Payment> saved = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    razorpay = new StubRazorpayGatewayClient();
    service = build(razorpay);
    when(store.insert(any()))
        .thenAnswer(
            inv -> {
              saved.set(inv.getArgument(0));
              return inv.getArgument(0);
            });
    when(store.update(any()))
        .thenAnswer(
            inv -> {
              saved.set(inv.getArgument(0));
              return inv.getArgument(0);
            });
    when(store.findByOrderId(any())).thenAnswer(inv -> Optional.ofNullable(saved.get()));
    when(store.findById(any()))
        .thenAnswer(
            inv ->
                saved.get() != null && saved.get().id().equals(inv.getArgument(0))
                    ? Optional.of(saved.get())
                    : Optional.empty());
    when(store.findByRazorpayOrderId(anyString()))
        .thenAnswer(
            inv ->
                saved.get() != null && inv.getArgument(0).equals(saved.get().razorpayOrderId())
                    ? Optional.of(saved.get())
                    : Optional.empty());
    when(store.findByRazorpayPaymentId(anyString()))
        .thenAnswer(
            inv ->
                saved.get() != null && inv.getArgument(0).equals(saved.get().razorpayPaymentId())
                    ? Optional.of(saved.get())
                    : Optional.empty());
  }

  private PaymentService build(RazorpayGatewayPort rz) {
    return new PaymentService(
        store,
        rz,
        wallet,
        orders,
        orderStatus,
        ledger,
        org.mockito.Mockito.mock(RefundFacadeService.class),
        new ObjectMapper(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        null);
  }

  @Test
  void initiateValidationsAndOwnership() {
    assertThatThrownBy(() -> service.initiate(customer, null, 100L, "INR", "UPI"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.initiate(customer, orderId, 0L, "INR", "UPI"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_AMOUNT");
    when(orders.findById(orderId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.initiate(customer, orderId, 100L, "INR", "UPI"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ORDER_NOT_FOUND");
    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(
                new OrderSnapshot(orderId, UUID.randomUUID(), "UPI", 100, 0, "PAYMENT_PENDING")));
    assertThatThrownBy(() -> service.initiate(customer, orderId, 100L, "INR", "UPI"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ORDER_NOT_YOURS");
  }

  @Test
  void initiateRejectsAlreadyInitiatedAndInvalidAmount() {
    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(new OrderSnapshot(orderId, customerId, "UPI", 1000, 0, "PAYMENT_PENDING")));
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(0L);
    service.initiate(customer, orderId, 1000L, "INR", "UPI");
    assertThatThrownBy(() -> service.initiate(customer, orderId, 1000L, "INR", "UPI"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAYMENT_ALREADY_INITIATED");

    saved.set(null);
    assertThatThrownBy(() -> service.initiate(customer, orderId, 999L, "INR", "UPI"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_AMOUNT");
  }

  @Test
  void initiateUsesExistingWalletAppliedWithoutRedebit() {
    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(
                new OrderSnapshot(orderId, customerId, "UPI", 44500, 5000, "PAYMENT_PENDING")));
    Map<String, Object> data = service.initiate(customer, orderId, 49500L, null, null);
    assertThat(data.get("wallet_deducted")).isEqualTo(new BigDecimal("50.00"));
    assertThat(data.get("gateway_amount_paise")).isEqualTo(44500L);
    verify(wallet, org.mockito.Mockito.never()).debitForOrder(any(), any(), anyLong(), anyString());
  }

  @Test
  void initiateNetAmountSkipsWalletPortion() {
    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(
                new OrderSnapshot(orderId, customerId, "CARD", 44500, 5000, "PAYMENT_PENDING")));
    Map<String, Object> data = service.initiate(customer, orderId, 44500L, "INR", "CARD");
    assertThat(data.get("gateway_amount_paise")).isEqualTo(44500L);
    assertThat(data.get("wallet_deducted")).isEqualTo(new BigDecimal("0.00"));
  }

  @Test
  void walletOnlyCapturesImmediately() {
    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(new OrderSnapshot(orderId, customerId, "UPI", 5000, 0, "PAYMENT_PENDING")));
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(5000L);
    Map<String, Object> data = service.initiate(customer, orderId, 5000L, "INR", "UPI");
    assertThat(data.get("gateway_amount_paise")).isEqualTo(0L);
    assertThat(saved.get().status()).isEqualTo(PaymentStatus.CAPTURED);
    assertThat(saved.get().method()).isEqualTo(PaymentMethod.WALLET_ONLY);
    verify(orderStatus).onCaptured(orderId, null);
  }

  @Test
  void initiateRazorpayErrors() {
    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(new OrderSnapshot(orderId, customerId, "UPI", 100, 0, "PAYMENT_PENDING")));
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(0L);
    PaymentService failing = build(new StubRazorpayGatewayClient("k", "s", "w", true));
    assertThatThrownBy(() -> failing.initiate(customer, orderId, 100L, "INR", "UPI"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_ERROR");

    when(razorpayMock.keyId()).thenReturn("k");
    when(razorpayMock.createOrder(any(), anyLong())).thenThrow(new RuntimeException("boom"));
    PaymentService rt = build(razorpayMock);
    assertThatThrownBy(() -> rt.initiate(customer, orderId, 100L, "INR", "UPI"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_ERROR");
  }

  @Test
  void reinitiateAfterFailed() {
    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(new OrderSnapshot(orderId, customerId, "UPI", 1000, 0, "PAYMENT_PENDING")));
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(0L);
    service.initiate(customer, orderId, 1000L, "INR", "UPI");
    saved.get().fail("declined", NOW);
    store.update(saved.get());

    Map<String, Object> data = service.initiate(customer, orderId, 1000L, "INR", "UPI");
    assertThat(data.get("payment_id")).isEqualTo(saved.get().id());
    assertThat(saved.get().status()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  void reinitiateAfterFailedReusesWalletDebit() {
    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(
                new OrderSnapshot(orderId, customerId, "UPI", 10000, 0, "PAYMENT_PENDING")));
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(5000L);
    service.initiate(customer, orderId, 10000L, "INR", "UPI");
    assertThat(saved.get().walletPortionPaise()).isEqualTo(5000L);
    saved.get().fail("declined", NOW);
    store.update(saved.get());

    service.initiate(customer, orderId, 10000L, "INR", "UPI");
    assertThat(saved.get().walletPortionPaise()).isEqualTo(5000L);
    assertThat(saved.get().gatewayPortionPaise()).isEqualTo(5000L);
    // only one debit call — re-initiate must not drain wallet again
    verify(wallet, times(1)).debitForOrder(any(), any(), anyLong(), anyString());
  }

  @Test
  void verifyBranches() {
    assertThatThrownBy(() -> service.verify(customer, "p", null, "s"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.verify(customer, null, "o", "s"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.verify(customer, "p", "", "s"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.verify(customer, "", "o", "s"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.verify(customer, "p", "o", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAYMENT_SIGNATURE_INVALID");
    assertThatThrownBy(() -> service.verify(customer, "p", "o", " "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAYMENT_SIGNATURE_INVALID");
    assertThatThrownBy(() -> service.verify(customer, "p", "missing", "sig"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAYMENT_NOT_FOUND");

    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(new OrderSnapshot(orderId, customerId, "UPI", 1000, 0, "PAYMENT_PENDING")));
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(0L);
    service.initiate(customer, orderId, 1000L, "INR", "UPI");
    String payId = "pay_1";
    String sig = razorpay.signPayment(saved.get().razorpayOrderId(), payId);
    service.verify(customer, payId, saved.get().razorpayOrderId(), sig);
    assertThatThrownBy(() -> service.verify(customer, payId, saved.get().razorpayOrderId(), sig))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAYMENT_ALREADY_VERIFIED");

    MedmatePrincipal other =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "x");
    saved.get().fail("x", NOW);
    store.update(saved.get());
    // recreate pending for ownership check on verify
    saved.set(null);
    service.initiate(customer, orderId, 1000L, "INR", "UPI");
    assertThatThrownBy(
            () ->
                service.verify(
                    other,
                    "pay_z",
                    saved.get().razorpayOrderId(),
                    razorpay.signPayment(saved.get().razorpayOrderId(), "pay_z")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void webhookCapturedFailedRefundUnknown() {
    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(new OrderSnapshot(orderId, customerId, "UPI", 1000, 0, "PAYMENT_PENDING")));
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(0L);
    service.initiate(customer, orderId, 1000L, "INR", "UPI");
    String rzOrder = saved.get().razorpayOrderId();

    String captured =
        """
        {"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_wh_1","order_id":"%s","fee":50}}}}
        """
            .formatted(rzOrder);
    Map<String, Object> ok =
        service.handleWebhook(sign(captured), captured.getBytes(StandardCharsets.UTF_8));
    assertThat(ok.get("processed")).isEqualTo(true);
    assertThat(saved.get().status()).isEqualTo(PaymentStatus.CAPTURED);

    String failedBody =
        """
        {"event":"payment.failed","payload":{"payment":{"entity":{"id":"pay_f","order_id":"%s","error_description":"bank"}}}}
        """
            .formatted(rzOrder);
    // already captured — no-op
    Map<String, Object> ignored =
        service.handleWebhook(sign(failedBody), failedBody.getBytes(StandardCharsets.UTF_8));
    assertThat(ignored.get("processed")).isEqualTo(false);

    saved.set(null);
    service.initiate(customer, orderId, 1000L, "INR", "UPI");
    String failNew =
        """
        {"event":"payment.failed","payload":{"payment":{"entity":{"id":"pay_f2","order_id":"%s","error_code":"X"}}}}
        """
            .formatted(saved.get().razorpayOrderId());
    Map<String, Object> failed =
        service.handleWebhook(sign(failNew), failNew.getBytes(StandardCharsets.UTF_8));
    assertThat(failed.get("processed")).isEqualTo(true);
    assertThat(saved.get().status()).isEqualTo(PaymentStatus.FAILED);
    verify(orderStatus).onFailed(eq(orderId), anyString());

    String refund =
        """
        {"event":"refund.processed","payload":{"refund":{"entity":{"payment_id":"pay_r"}}}}
        """;
    // RefundFacadeService is mocked in build(); webhook still returns ack from mock default null
    // — use a dedicated service with stubbed facade for this branch.
    RefundFacadeService refundFacade = org.mockito.Mockito.mock(RefundFacadeService.class);
    when(refundFacade.completeFromWebhook(any()))
        .thenReturn(Map.of("event", "refund.processed", "processed", true));
    PaymentService withRefunds =
        new PaymentService(
            store,
            razorpay,
            wallet,
            orders,
            orderStatus,
            ledger,
            refundFacade,
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            null);
    assertThat(
            withRefunds
                .handleWebhook(sign(refund), refund.getBytes(StandardCharsets.UTF_8))
                .get("processed"))
        .isEqualTo(true);

    String unknown = "{\"event\":\"order.paid\"}";
    assertThatThrownBy(
            () -> service.handleWebhook(sign(unknown), unknown.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("UNKNOWN_EVENT");
              assertThat(ae.httpStatus()).isEqualTo(200);
            });

    String noEvent = "{}";
    assertThat(
            service
                .handleWebhook(sign(noEvent), noEvent.getBytes(StandardCharsets.UTF_8))
                .get("event"))
        .isNull();

    assertThatThrownBy(() -> service.handleWebhook(sign("{"), "{".getBytes(StandardCharsets.UTF_8)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void getPaymentRolesAndMissing() {
    assertThatThrownBy(() -> service.getPayment(customer, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.getPayment(customer, UUID.randomUUID()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAYMENT_NOT_FOUND");

    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(new OrderSnapshot(orderId, customerId, "UPI", 1000, 0, "PAYMENT_PENDING")));
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(0L);
    service.initiate(customer, orderId, 1000L, "INR", "UPI");

    Map<String, Object> own = service.getPayment(customer, saved.get().id());
    assertThat(own.get("status")).isEqualTo("PENDING");

    MedmatePrincipal finance =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "a");
    assertThat(service.getPayment(finance, saved.get().id()).get("customer_id"))
        .isEqualTo(customerId);

    MedmatePrincipal rider =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.RIDER, null, TokenScope.FULL, "r");
    assertThatThrownBy(() -> service.getPayment(rider, saved.get().id()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void requireCustomerOnMutators() {
    MedmatePrincipal admin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "a");
    assertThatThrownBy(() -> service.initiate(admin, orderId, 100L, "INR", "UPI"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void initiateReplaysAndRejectsConflictingIdempotencyKey() {
    Payment existing =
        new Payment(
            UUID.randomUUID(),
            orderId,
            customerId,
            1000,
            0,
            1000,
            "INR",
            PaymentMethod.UPI,
            PaymentStatus.PENDING,
            "order_rz",
            null,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            "idem-1",
            NOW,
            NOW);
    when(store.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(existing));
    Map<String, Object> replay = service.initiate(customer, orderId, 1000L, "INR", "UPI", "idem-1");
    assertThat(replay.get("payment_id")).isEqualTo(existing.id());

    when(orders.findById(orderId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.initiate(customer, orderId, 1000L, "INR", "UPI", "  "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ORDER_NOT_FOUND");
    when(store.findByIdempotencyKey("other")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.initiate(customer, orderId, 1000L, "INR", "UPI", "other"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ORDER_NOT_FOUND");

    assertThatThrownBy(
            () -> service.initiate(customer, UUID.randomUUID(), 1000L, "INR", "UPI", "idem-1"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
    Map<String, Object> nullOrderReplay =
        service.initiate(customer, null, 1000L, "INR", "UPI", "idem-1");
    assertThat(nullOrderReplay.get("payment_id")).isEqualTo(existing.id());
  }

  @Test
  void webhookMissingPaymentNoOp() {
    String body =
        """
        {"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_ghost","order_id":"order_ghost"}}}}
        """;
    Map<String, Object> ack =
        service.handleWebhook(sign(body), body.getBytes(StandardCharsets.UTF_8));
    assertThat(ack.get("processed")).isEqualTo(false);
  }

  private static String sign(String body) {
    return StubRazorpayGatewayClient.hmacHex(
        StubRazorpayGatewayClient.DEFAULT_WEBHOOK_SECRET, body);
  }
}

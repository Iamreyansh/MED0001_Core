package com.nammamedmate.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
class PaymentServiceFinalCoverageTest {

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
    service =
        new PaymentService(
            store,
            razorpay,
            wallet,
            orders,
            orderStatus,
            ledger,
            org.mockito.Mockito.mock(RefundFacadeService.class),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new BigDecimal("8.00"));
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

  @Test
  void codMethodOnNonCodOrderRejected() {
    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(new OrderSnapshot(orderId, customerId, "UPI", 100, 0, "PAYMENT_PENDING")));
    assertThatThrownBy(() -> service.initiate(customer, orderId, 100L, "INR", "COD"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("COD_ORDER_NO_PAYMENT");
  }

  @Test
  void razorpayAppExceptionOtherCodesRethrown() {
    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(new OrderSnapshot(orderId, customerId, "UPI", 100, 0, "PAYMENT_PENDING")));
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(0L);
    when(razorpayMock.keyId()).thenReturn("k");
    when(razorpayMock.createOrder(any(), anyLong()))
        .thenThrow(new AppException("VALIDATION_ERROR", "bad", 400));
    PaymentService svc =
        new PaymentService(
            store,
            razorpayMock,
            wallet,
            orders,
            orderStatus,
            ledger,
            org.mockito.Mockito.mock(RefundFacadeService.class),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new BigDecimal("8.00"));
    assertThatThrownBy(() -> svc.initiate(customer, orderId, 100L, "INR", "UPI"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void razorpayNullKeyIdKeepsConfigured() {
    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(new OrderSnapshot(orderId, customerId, "UPI", 100, 0, "PAYMENT_PENDING")));
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(0L);
    when(razorpayMock.keyId()).thenReturn("fallback");
    when(razorpayMock.createOrder(any(), anyLong()))
        .thenReturn(new RazorpayGatewayPort.CreateOrderResult("order_x", 100, null));
    PaymentService svc =
        new PaymentService(
            store,
            razorpayMock,
            wallet,
            orders,
            orderStatus,
            ledger,
            org.mockito.Mockito.mock(RefundFacadeService.class),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new BigDecimal("8.00"));
    Map<String, Object> data = svc.initiate(customer, orderId, 100L, " ", "UPI");
    assertThat(data.get("razorpay_key_id")).isEqualTo("fallback");
  }

  @Test
  void reinitiateFailedAsWalletOnly() {
    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(new OrderSnapshot(orderId, customerId, "UPI", 500, 0, "PAYMENT_PENDING")));
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(0L);
    service.initiate(customer, orderId, 500L, "INR", "UPI");
    saved.get().fail("x", NOW);
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(500L);
    Map<String, Object> data = service.initiate(customer, orderId, 500L, "", "UPI");
    assertThat(data.get("gateway_amount_paise")).isEqualTo(0L);
    assertThat(saved.get().status()).isEqualTo(PaymentStatus.CAPTURED);
  }

  @Test
  void webhookNullBodyAndMissingPaymentId() {
    String empty = "{}";
    // null body after signature — use empty signed body then call with null via verify first...
    assertThatThrownBy(() -> service.handleWebhook("x", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("WEBHOOK_SIGNATURE_INVALID");

    String noPayId =
        """
        {"event":"payment.captured","payload":{"payment":{"entity":{"order_id":"order_only"}}}}
        """;
    Map<String, Object> ack =
        service.handleWebhook(sign(noPayId), noPayId.getBytes(StandardCharsets.UTF_8));
    assertThat(ack.get("processed")).isEqualTo(false);

    String noFee =
        """
        {"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_nf","order_id":"missing"}}}}
        """;
    assertThat(
            service
                .handleWebhook(sign(noFee), noFee.getBytes(StandardCharsets.UTF_8))
                .get("processed"))
        .isEqualTo(false);
  }

  @Test
  void webhookCapturedWithoutFeeUsesEstimate() {
    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(new OrderSnapshot(orderId, customerId, "UPI", 1000, 0, "PAYMENT_PENDING")));
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(0L);
    service.initiate(customer, orderId, 1000L, "INR", "UPI");
    String body =
        """
        {"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_est","order_id":"%s"}}}}
        """
            .formatted(saved.get().razorpayOrderId());
    Map<String, Object> ack =
        service.handleWebhook(sign(body), body.getBytes(StandardCharsets.UTF_8));
    assertThat(ack.get("processed")).isEqualTo(true);
    assertThat(saved.get().gatewayFeePaise()).isEqualTo(20L);
  }

  @Test
  void webhookFailedIdempotentAndLookupByPaymentId() {
    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(new OrderSnapshot(orderId, customerId, "UPI", 1000, 0, "PAYMENT_PENDING")));
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(0L);
    service.initiate(customer, orderId, 1000L, "INR", "UPI");
    Payment pending = saved.get();
    pending.fail("pay_fail_1", "bank", null, NOW);
    store.update(pending);

    String idem =
        """
        {"event":"payment.failed","payload":{"payment":{"entity":{"id":"pay_fail_1","order_id":"%s"}}}}
        """
            .formatted(pending.razorpayOrderId());
    assertThat(
            service
                .handleWebhook(sign(idem), idem.getBytes(StandardCharsets.UTF_8))
                .get("processed"))
        .isEqualTo(false);

    // reset to pending without razorpay order match path via payment id only
    saved.set(
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
            null,
            "pay_only",
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            NOW,
            NOW));
    String byPay =
        """
        {"event":"payment.failed","payload":{"payment":{"entity":{"id":"pay_only"}}}}
        """;
    assertThat(
            service
                .handleWebhook(sign(byPay), byPay.getBytes(StandardCharsets.UTF_8))
                .get("processed"))
        .isEqualTo(true);

    String missing =
        """
        {"event":"payment.failed","payload":{"payment":{"entity":{"id":"pay_ghost2"}}}}
        """;
    assertThat(
            service
                .handleWebhook(sign(missing), missing.getBytes(StandardCharsets.UTF_8))
                .get("processed"))
        .isEqualTo(false);
  }

  @Test
  void getPaymentAdminSuper() {
    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(new OrderSnapshot(orderId, customerId, "UPI", 1000, 0, "PAYMENT_PENDING")));
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(0L);
    service.initiate(customer, orderId, 1000L, "INR", "UPI");
    MedmatePrincipal admin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "a");
    assertThat(service.getPayment(admin, saved.get().id()).get("status")).isEqualTo("PENDING");
  }

  @Test
  void remainingPaymentServiceBranches() {
    // blank method uses order method
    when(orders.findById(orderId))
        .thenReturn(
            Optional.of(
                new OrderSnapshot(orderId, customerId, "CARD", 1000, 0, "PAYMENT_PENDING")));
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(0L);
    Map<String, Object> init = service.initiate(customer, orderId, 1000L, "INR", "  ");
    assertThat(init.get("method")).isEqualTo("CARD");

    // PAYMENT_INITIATION_FAILED remapped, then blank keyId keeps fallback
    when(razorpayMock.keyId()).thenReturn("k");
    when(razorpayMock.createOrder(any(), anyLong()))
        .thenThrow(new AppException("PAYMENT_INITIATION_FAILED", "fail", 502))
        .thenReturn(new RazorpayGatewayPort.CreateOrderResult("order_b", 1000, "  "));
    PaymentService remap =
        new PaymentService(
            store,
            razorpayMock,
            wallet,
            orders,
            orderStatus,
            ledger,
            org.mockito.Mockito.mock(RefundFacadeService.class),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            BigDecimal.ZERO);
    saved.set(null);
    assertThatThrownBy(() -> remap.initiate(customer, orderId, 1000L, "INR", "UPI"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RAZORPAY_ERROR");

    saved.set(null);
    Map<String, Object> keyed = remap.initiate(customer, orderId, 1000L, "INR", "UPI");
    assertThat(keyed.get("razorpay_key_id")).isEqualTo("k");

    // zero commission ledger + null principal + detail null fee
    saved.set(null);
    PaymentService zeroComm =
        new PaymentService(
            store,
            razorpay,
            wallet,
            orders,
            orderStatus,
            ledger,
            org.mockito.Mockito.mock(RefundFacadeService.class),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            BigDecimal.ZERO);
    zeroComm.initiate(customer, orderId, 1000L, "INR", "UPI");
    assertThat(zeroComm.getPayment(customer, saved.get().id()).get("gateway_fee")).isNull();

    assertThatThrownBy(() -> service.initiate(null, orderId, 1000L, "INR", "UPI"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    // blank event text
    String blankEvent = "{\"event\":\"   \"}";
    assertThat(
            service
                .handleWebhook(sign(blankEvent), blankEvent.getBytes(StandardCharsets.UTF_8))
                .get("event"))
        .isNull();

    // captured already via order id without payment-id match
    saved.get().capture(null, null, 1L, null, NOW);
    store.update(saved.get());
    String body =
        """
        {"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_new","order_id":"%s","fee":null}}}}
        """
            .formatted(saved.get().razorpayOrderId());
    // status CAPTURED → no-op
    assertThat(
            service
                .handleWebhook(sign(body), body.getBytes(StandardCharsets.UTF_8))
                .get("processed"))
        .isEqualTo(false);

    // failed without payment id
    saved.set(null);
    zeroComm.initiate(customer, orderId, 1000L, "INR", "UPI");
    String failNoId =
        """
        {"event":"payment.failed","payload":{"payment":{"entity":{"order_id":"%s"}}}}
        """
            .formatted(saved.get().razorpayOrderId());
    assertThat(
            service
                .handleWebhook(sign(failNoId), failNoId.getBytes(StandardCharsets.UTF_8))
                .get("processed"))
        .isEqualTo(true);

    // estimate fee when gateway portion 0
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(1000L);
    saved.set(null);
    zeroComm.initiate(customer, orderId, 1000L, "INR", "UPI");
    assertThat(saved.get().gatewayPortionPaise()).isZero();

    // capture then getPayment with fee present
    saved.set(null);
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(0L);
    service.initiate(customer, orderId, 1000L, "INR", "UPI");
    String payId = "pay_fee_get";
    String sig = razorpay.signPayment(saved.get().razorpayOrderId(), payId);
    service.verify(customer, payId, saved.get().razorpayOrderId(), sig);
    assertThat(service.getPayment(customer, saved.get().id()).get("gateway_fee")).isNotNull();

    // captured webhook with payment id but no order id → no-op
    String orphan =
        """
        {"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_orphan_only"}}}}
        """;
    assertThat(
            service
                .handleWebhook(sign(orphan), orphan.getBytes(StandardCharsets.UTF_8))
                .get("processed"))
        .isEqualTo(false);

    // fee: null JSON
    saved.set(null);
    service.initiate(customer, orderId, 1000L, "INR", "UPI");
    String feeNull =
        """
        {"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_fn","order_id":"%s","fee":null}}}}
        """
            .formatted(saved.get().razorpayOrderId());
    // byPayId present but PENDING → continue capture path via byPayId
    saved.set(null);
    service.initiate(customer, orderId, 1000L, "INR", "UPI");
    Payment pending = saved.get();
    // simulate client set payment id before capture webhook
    pending.fail("pay_pending_id", "temp", null, NOW);
    pending =
        new Payment(
            pending.id(),
            pending.orderId(),
            pending.customerId(),
            pending.amountPaise(),
            pending.walletPortionPaise(),
            pending.gatewayPortionPaise(),
            pending.currency(),
            pending.method(),
            PaymentStatus.PENDING,
            pending.razorpayOrderId(),
            "pay_pending_id",
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            pending.createdAt(),
            NOW);
    saved.set(pending);
    String pendingCapture =
        """
        {"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_pending_id","order_id":"%s","fee":10}}}}
        """
            .formatted(pending.razorpayOrderId());
    assertThat(
            service
                .handleWebhook(
                    sign(pendingCapture), pendingCapture.getBytes(StandardCharsets.UTF_8))
                .get("processed"))
        .isEqualTo(true);

    // failed with error_description set (skip error_code branch)
    saved.set(null);
    service.initiate(customer, orderId, 1000L, "INR", "UPI");
    String withDesc =
        """
        {"event":"payment.failed","payload":{"payment":{"entity":{"id":"pay_desc","order_id":"%s","error_description":"insufficient funds"}}}}
        """
            .formatted(saved.get().razorpayOrderId());
    assertThat(
            service
                .handleWebhook(sign(withDesc), withDesc.getBytes(StandardCharsets.UTF_8))
                .get("processed"))
        .isEqualTo(true);

    // failed with neither order nor payment match, paymentId null
    String noIds =
        """
        {"event":"payment.failed","payload":{"payment":{"entity":{}}}}
        """;
    assertThat(
            service
                .handleWebhook(sign(noIds), noIds.getBytes(StandardCharsets.UTF_8))
                .get("processed"))
        .isEqualTo(false);

    String nullEvent = "{\"event\":null}";
    assertThat(
            service
                .handleWebhook(sign(nullEvent), nullEvent.getBytes(StandardCharsets.UTF_8))
                .get("event"))
        .isNull();

    // fee present but not a number
    saved.set(null);
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(0L);
    service.initiate(customer, orderId, 1000L, "INR", "UPI");
    String feeStr =
        """
        {"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_fs","order_id":"%s","fee":"x"}}}}
        """
            .formatted(saved.get().razorpayOrderId());
    assertThat(
            service
                .handleWebhook(sign(feeStr), feeStr.getBytes(StandardCharsets.UTF_8))
                .get("processed"))
        .isEqualTo(true);
  }

  @Test
  void amountNullTreatedInvalid() {
    assertThatThrownBy(() -> service.initiate(customer, orderId, null, "INR", "UPI"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_AMOUNT");
  }

  private static String sign(String body) {
    return StubRazorpayGatewayClient.hmacHex(
        StubRazorpayGatewayClient.DEFAULT_WEBHOOK_SECRET, body);
  }
}

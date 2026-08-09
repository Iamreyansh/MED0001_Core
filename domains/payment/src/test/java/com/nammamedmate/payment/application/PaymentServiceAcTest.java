package com.nammamedmate.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
class PaymentServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Mock private PaymentStore store;
  @Mock private WalletPort wallet;
  @Mock private OrderLookupPort orders;
  @Mock private OrderPaymentStatusPort orderStatus;
  @Mock private FinancialLedgerWriterPort ledger;

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
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
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
            clock,
            new BigDecimal("8.00"));
    when(store.insert(any()))
        .thenAnswer(
            inv -> {
              Payment p = inv.getArgument(0);
              saved.set(p);
              return p;
            });
    when(store.update(any()))
        .thenAnswer(
            inv -> {
              Payment p = inv.getArgument(0);
              saved.set(p);
              return p;
            });
    when(store.findByOrderId(any())).thenAnswer(inv -> Optional.ofNullable(saved.get()));
    when(store.findById(any()))
        .thenAnswer(
            inv -> {
              Payment p = saved.get();
              if (p != null && p.id().equals(inv.getArgument(0))) {
                return Optional.of(p);
              }
              return Optional.empty();
            });
    when(store.findByRazorpayOrderId(anyString()))
        .thenAnswer(
            inv -> {
              Payment p = saved.get();
              if (p != null && inv.getArgument(0).equals(p.razorpayOrderId())) {
                return Optional.of(p);
              }
              return Optional.empty();
            });
    when(store.findByRazorpayPaymentId(anyString()))
        .thenAnswer(
            inv -> {
              Payment p = saved.get();
              if (p != null && inv.getArgument(0).equals(p.razorpayPaymentId())) {
                return Optional.of(p);
              }
              return Optional.empty();
            });
  }

  @Test
  void ac001_initiateCreatesRazorpayOrderAndPendingPayment() {
    when(orders.findById(orderId)).thenReturn(Optional.of(snap("UPI", 49500, 0)));
    when(wallet.debitForOrder(eq(customerId), eq(orderId), eq(49500L), anyString())).thenReturn(0L);

    Map<String, Object> data = service.initiate(customer, orderId, 49500L, "INR", "UPI");

    assertThat(data.get("razorpay_order_id")).asString().startsWith("order_stub_");
    assertThat(data.get("razorpay_key_id")).isEqualTo(StubRazorpayGatewayClient.DEFAULT_KEY_ID);
    assertThat(saved.get().status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(saved.get().method()).isEqualTo(PaymentMethod.UPI);
  }

  @Test
  void ac002_verifyValidSignatureCapturesAndAdvancesOrder() {
    seedPendingPayment();
    String payId = "pay_valid_001";
    String sig = razorpay.signPayment(saved.get().razorpayOrderId(), payId);

    Map<String, Object> data = service.verify(customer, payId, saved.get().razorpayOrderId(), sig);

    assertThat(data.get("payment_status")).isEqualTo("CAPTURED");
    assertThat(saved.get().status()).isEqualTo(PaymentStatus.CAPTURED);
    verify(orderStatus).onCaptured(orderId, payId);
    verify(ledger)
        .append(eq("ORDER_GMV"), any(), eq("PAYMENT"), eq(49500L), eq(0L), anyString(), any());
    // STORY-008 AC-001: COMMISSION is credit (8% of 49500 = 3960)
    verify(ledger)
        .append(eq("COMMISSION"), any(), eq("PAYMENT"), eq(3960L), eq(0L), anyString(), any());
    verify(ledger)
        .append(eq("GATEWAY_FEE"), any(), eq("PAYMENT"), eq(0L), anyLong(), anyString(), any());
  }

  @Test
  void ac003_verifyInvalidSignatureReturns422() {
    seedPendingPayment();

    assertThatThrownBy(
            () -> service.verify(customer, "pay_x", saved.get().razorpayOrderId(), "bad_signature"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PAYMENT_SIGNATURE_INVALID");
    assertThat(saved.get().status()).isEqualTo(PaymentStatus.PENDING);
    verify(orderStatus, never()).onCaptured(any(), any());
  }

  @Test
  void ac004_duplicateCapturedWebhookIsIdempotentNoOp() {
    seedPendingPayment();
    String payId = "pay_dup_001";
    String sig = razorpay.signPayment(saved.get().razorpayOrderId(), payId);
    service.verify(customer, payId, saved.get().razorpayOrderId(), sig);

    String body =
        """
        {"event":"payment.captured","payload":{"payment":{"entity":{"id":"%s","order_id":"%s","fee":100}}}}
        """
            .formatted(payId, saved.get().razorpayOrderId());
    String header =
        StubRazorpayGatewayClient.hmacHex(StubRazorpayGatewayClient.DEFAULT_WEBHOOK_SECRET, body);

    Map<String, Object> ack = service.handleWebhook(header, body.getBytes(StandardCharsets.UTF_8));

    assertThat(ack.get("processed")).isEqualTo(false);
    assertThat(ack.get("event")).isEqualTo("payment.captured");
  }

  @Test
  void ac005_invalidWebhookSignatureReturns400() {
    assertThatThrownBy(() -> service.handleWebhook("nope", "{}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("WEBHOOK_SIGNATURE_INVALID");
              assertThat(ae.httpStatus()).isEqualTo(400);
            });
  }

  @Test
  void ac006_codInitiateReturns422() {
    when(orders.findById(orderId)).thenReturn(Optional.of(snap("COD", 22125, 0)));

    assertThatThrownBy(() -> service.initiate(customer, orderId, 22125L, "INR", "COD"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("COD_ORDER_NO_PAYMENT");
    verify(wallet, never()).debitForOrder(any(), any(), anyLong(), anyString());
  }

  @Test
  void ac007_hybridWalletShowsDeductedAndGatewayRemainder() {
    when(orders.findById(orderId)).thenReturn(Optional.of(snap("UPI", 49500, 0)));
    when(wallet.debitForOrder(eq(customerId), eq(orderId), eq(49500L), anyString()))
        .thenReturn(5000L);

    Map<String, Object> data = service.initiate(customer, orderId, 49500L, "INR", "UPI");

    assertThat(data.get("wallet_deducted")).isEqualTo(new BigDecimal("50.00"));
    assertThat(data.get("gateway_amount_paise")).isEqualTo(44500L);
    assertThat(saved.get().walletPortionPaise()).isEqualTo(5000L);
    assertThat(saved.get().gatewayPortionPaise()).isEqualTo(44500L);
  }

  @Test
  void ac008_getPaymentCrossCustomerForbidden() {
    seedPendingPayment();
    MedmatePrincipal other =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j2");

    assertThatThrownBy(() -> service.getPayment(other, saved.get().id()))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  private void seedPendingPayment() {
    when(orders.findById(orderId)).thenReturn(Optional.of(snap("UPI", 49500, 0)));
    when(wallet.debitForOrder(any(), any(), anyLong(), anyString())).thenReturn(0L);
    service.initiate(customer, orderId, 49500L, "INR", "UPI");
  }

  private OrderSnapshot snap(String method, long total, long walletApplied) {
    return new OrderSnapshot(orderId, customerId, method, total, walletApplied, "PAYMENT_PENDING");
  }
}

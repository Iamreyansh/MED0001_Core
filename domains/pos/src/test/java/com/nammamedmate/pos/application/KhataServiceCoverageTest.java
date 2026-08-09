package com.nammamedmate.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pos.adapter.out.export.SimpleXlsxExporter;
import com.nammamedmate.pos.application.port.out.InvoiceStore;
import com.nammamedmate.pos.application.port.out.KhataStore;
import com.nammamedmate.pos.application.port.out.OfferStore;
import com.nammamedmate.pos.application.port.out.PosCartStore;
import com.nammamedmate.pos.application.port.out.PosKhataPort;
import com.nammamedmate.pos.application.port.out.PosNotificationPort;
import com.nammamedmate.pos.application.port.out.PosPlanPort;
import com.nammamedmate.pos.application.port.out.StockDeductionPort;
import com.nammamedmate.pos.domain.KhataEntryType;
import com.nammamedmate.pos.domain.PosCart;
import com.nammamedmate.pos.domain.PosCartItem;
import com.nammamedmate.pos.domain.PosCartStatus;
import com.nammamedmate.pos.domain.ReminderTemplate;
import com.nammamedmate.pos.domain.ShareChannel;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KhataServiceCoverageTest {

  static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Mock KhataStore store;
  @Mock PosPlanPort plan;
  @Mock PosNotificationPort notifications;
  @Mock RateLimiter rateLimiter;
  @Mock PosCartStore cartStore;
  @Mock InvoiceStore invoiceStore;
  @Mock StockDeductionPort stock;
  @Mock PosKhataPort khata;
  @Mock OfferStore offerStore;

  KhataService service;
  PosCheckoutService checkout;
  UUID pharmacy = UUID.randomUUID();
  UUID customerId = UUID.randomUUID();
  UUID staff = UUID.randomUUID();
  MedmatePrincipal owner =
      new MedmatePrincipal(staff, AuthRole.PHARMACY_OWNER, pharmacy, TokenScope.FULL, "j");
  MedmatePrincipal admin =
      new MedmatePrincipal(staff, AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  MedmatePrincipal adminSupport =
      new MedmatePrincipal(staff, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
  MedmatePrincipal customer =
      new MedmatePrincipal(staff, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    when(plan.starterFeaturesEnabled()).thenReturn(true);
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    when(store.customerKnownToPharmacy(pharmacy, customerId)).thenReturn(true);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    service =
        new KhataService(store, plan, notifications, new SimpleXlsxExporter(), rateLimiter, clock);
    checkout =
        new PosCheckoutService(
            cartStore, invoiceStore, stock, khata, offerStore, rateLimiter, clock);
    when(khata.outstandingPaise(any(), any())).thenReturn(0L);
    when(khata.creditLimitPaise(any(), any())).thenReturn(5_000_000L);
  }

  @Test
  void enumsTouched() {
    assertThat(KhataEntryType.values()).hasSize(2);
    assertThat(ReminderTemplate.values()).hasSize(2);
  }

  @Test
  void creditLimitExceededAtCheckout() {
    UUID cartId = UUID.randomUUID();
    PosCart cart =
        new PosCart(
            cartId,
            pharmacy,
            staff,
            customerId,
            "A",
            "+91",
            null,
            null,
            BigDecimal.ZERO,
            0,
            1000,
            0,
            1000,
            PosCartStatus.ACTIVE,
            NOW.plusSeconds(100),
            null,
            null,
            NOW,
            NOW);
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.of(cart));
    when(cartStore.listItems(cartId))
        .thenReturn(
            List.of(
                PosCartItem.compute(
                    UUID.randomUUID(),
                    cartId,
                    UUID.randomUUID(),
                    "X",
                    UUID.randomUUID(),
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    1,
                    false,
                    1000L,
                    0,
                    false,
                    1,
                    null,
                    NOW)));
    when(khata.outstandingPaise(pharmacy, customerId)).thenReturn(4_999_500L);
    when(khata.creditLimitPaise(pharmacy, customerId)).thenReturn(5_000_000L);

    assertThatThrownBy(
            () -> checkout.checkout(owner, cartId, "CREDIT", BigDecimal.ZERO, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CREDIT_LIMIT_EXCEEDED");
  }

  @Test
  void detailCustomerNotFoundAndAdminPharmacyRequired() {
    when(store.customerKnownToPharmacy(pharmacy, customerId)).thenReturn(false);
    assertThatThrownBy(() -> service.detail(owner, customerId, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");

    when(store.customerKnownToPharmacy(pharmacy, customerId)).thenReturn(true);
    when(store.findCustomer(customerId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.detail(owner, customerId, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");

    assertThatThrownBy(() -> service.list(admin, false, null, null, 1, 20, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.list(customer, false, null, null, 1, 20, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void repayValidationBranches() {
    when(store.findCustomer(customerId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.repay(owner, customerId, Map.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");

    when(store.findCustomer(customerId))
        .thenReturn(Optional.of(new KhataStore.CustomerInfo(customerId, "A", "+91")));
    assertThatThrownBy(() -> service.repay(owner, customerId, Map.of("payment_mode", "CASH")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.repay(owner, customerId, Map.of("amount", 10, "payment_mode", "X")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.repay(
                    owner,
                    customerId,
                    Map.of("amount", 10, "payment_mode", "CASH", "note", "x".repeat(301))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.repay(owner, customerId, Map.of("amount", 0, "payment_mode", "CASH")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.repay(owner, customerId, Map.of("amount", "nope", "payment_mode", "CASH")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.repay(
                    owner,
                    customerId,
                    Map.of("amount", new BigDecimal("10.00"), "payment_mode", "")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void remindNoBalanceAndWhatsappFallbackToSms() {
    when(store.findCustomer(customerId))
        .thenReturn(Optional.of(new KhataStore.CustomerInfo(customerId, "A", "+91")));
    when(store.outstandingPaise(pharmacy, customerId)).thenReturn(0L);
    assertThatThrownBy(
            () ->
                service.remind(
                    owner, customerId, Map.of("channel", "WHATSAPP", "message_template", "POLITE")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NO_OUTSTANDING_BALANCE");

    when(store.outstandingPaise(pharmacy, customerId)).thenReturn(100L);
    when(store.lastReminderAt(pharmacy, customerId)).thenReturn(Optional.empty());
    when(notifications.sendKhataReminder(
            any(), any(), eq(ShareChannel.WHATSAPP), any(), any(), anyLong()))
        .thenThrow(new AppException("CHANNEL_UNAVAILABLE", "down", 503));
    when(notifications.sendKhataReminder(
            any(), any(), eq(ShareChannel.SMS), any(), any(), anyLong()))
        .thenReturn(new PosNotificationPort.ShareResult("sms_1", NOW));

    Map<String, Object> data =
        service.remind(
            owner, customerId, Map.of("channel", "WHATSAPP", "message_template", "FIRM"));
    assertThat(data.get("channel")).isEqualTo("SMS");
  }

  @Test
  void paymentHistoryListAndInvalidExport() {
    when(store.paymentHistory(any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of());
    when(store.countPaymentHistory(any(), any(), any(), any(), any())).thenReturn(0L);
    when(store.paymentHistoryTotalPaise(any(), any(), any(), any(), any())).thenReturn(0L);

    Object result = service.paymentHistory(owner, null, null, null, null, 1, 20, null, null);
    assertThat(result).isInstanceOf(KhataService.ListResult.class);
    assertThat(new KhataService.ListResult(null, null).data()).isEmpty();

    assertThatThrownBy(
            () -> service.paymentHistory(owner, null, null, null, null, 1, 20, "PDF", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void listNullDataAndRateLimit() {
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(() -> service.list(owner, false, null, null, 1, 20, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void adminListWithPharmacyId() {
    when(store.kpi(any(), any(), any())).thenReturn(new KhataStore.KpiSnapshot(0, 0, 0, 0, 0));
    when(store.aging(any(), any())).thenReturn(new KhataStore.AgingBuckets(0, 0, 0));
    when(store.listOutstanding(
            eq(pharmacy), eq(false), eq("oldest_bill"), any(), anyInt(), anyInt()))
        .thenReturn(List.of());
    when(store.countOutstanding(pharmacy, false, null)).thenReturn(0L);

    KhataService.ListResult result =
        service.list(admin, false, "oldest_bill", null, 1, 20, pharmacy);
    assertThat(result.meta().total()).isZero();
    service.list(adminSupport, false, null, null, 1, 20, pharmacy);
    when(store.findCustomer(customerId))
        .thenReturn(Optional.of(new KhataStore.CustomerInfo(customerId, "A", "+91")));
    when(store.outstandingPaise(pharmacy, customerId)).thenReturn(0L);
    when(store.creditLimitPaise(pharmacy, customerId)).thenReturn(5_000_000L);
    when(store.unpaidBills(any(), any(), any())).thenReturn(List.of());
    when(store.ledgerDesc(any(), any())).thenReturn(List.of());
    service.detail(adminSupport, customerId, pharmacy);
  }

  @Test
  void detailUnpaidAndZeroLimitAndPageDefaults() {
    when(store.findCustomer(customerId))
        .thenReturn(Optional.of(new KhataStore.CustomerInfo(customerId, "A", "+91")));
    when(store.outstandingPaise(pharmacy, customerId)).thenReturn(100L);
    when(store.creditLimitPaise(pharmacy, customerId)).thenReturn(0L);
    when(store.unpaidBills(eq(pharmacy), eq(customerId), any()))
        .thenReturn(
            List.of(
                new KhataStore.UnpaidBillRow(
                    UUID.randomUUID(), "INV-1", LocalDate.of(2026, 5, 1), 50L, 40),
                new KhataStore.UnpaidBillRow(null, "INV-2", LocalDate.of(2026, 7, 20), 50L, 4)));
    when(store.ledgerDesc(pharmacy, customerId)).thenReturn(List.of());

    Map<String, Object> data = service.detail(owner, customerId, null);
    assertThat(data.get("summary")).isInstanceOf(Map.class);

    when(store.kpi(any(), any(), any())).thenReturn(new KhataStore.KpiSnapshot(0, 0, 0, 0, 0));
    when(store.aging(any(), any())).thenReturn(new KhataStore.AgingBuckets(0, 0, 0));
    when(store.listOutstanding(any(), anyBoolean(), any(), any(), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new KhataStore.CustomerOutstandingRow(
                    customerId, "A", "+91", 100, null, 0, false)));
    when(store.countOutstanding(any(), anyBoolean(), any())).thenReturn(1L);
    service.list(owner, false, "outstanding_asc", "q", 0, 0, null);
    service.list(owner, null, null, null, 1, 200, null);
  }

  @Test
  void repayAndRemindEdgeBranches() {
    when(store.findCustomer(customerId))
        .thenReturn(Optional.of(new KhataStore.CustomerInfo(customerId, "A", "+91")));
    when(store.outstandingPaise(pharmacy, customerId)).thenReturn(10_000L);
    when(store.recordRepayment(any(), any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(
            new KhataStore.RepaymentResult(
                UUID.randomUUID(), "RCPT-1", "A", 1000L, "UPI", 10_000L, 9_000L, "u", NOW));

    assertThatThrownBy(
            () ->
                service.repay(
                    owner,
                    customerId,
                    Map.of(
                        "amount", "10", "payment_mode", "UPI", "reference_number", "x".repeat(51))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThat(
            service.repay(
                owner,
                customerId,
                Map.of(
                    "amount",
                    10,
                    "payment_mode",
                    "CARD",
                    "note",
                    "ok",
                    "reference_number",
                    "UTR1")))
        .containsKey("receipt_number");

    assertThatThrownBy(() -> service.repay(owner, customerId, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.outstandingPaise(pharmacy, customerId)).thenReturn(100L);
    when(store.lastReminderAt(pharmacy, customerId))
        .thenReturn(Optional.of(NOW.minusSeconds(90000)));
    when(notifications.sendKhataReminder(any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(new PosNotificationPort.ShareResult("ok", NOW));
    assertThat(
            service.remind(
                owner, customerId, Map.of("channel", "SMS", "message_template", "POLITE")))
        .containsKey("message_id");
    assertThatThrownBy(() -> service.remind(owner, customerId, Map.of("channel", "WHATSAPP")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.remind(owner, customerId, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.remind(
                    owner, customerId, Map.of("channel", "EMAIL", "message_template", "POLITE")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.remind(
                    owner, customerId, Map.of("channel", "NOPE", "message_template", "POLITE")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.findCustomer(customerId))
        .thenReturn(Optional.of(new KhataStore.CustomerInfo(customerId, "A", null)));
    assertThatThrownBy(
            () ->
                service.remind(
                    owner, customerId, Map.of("channel", "SMS", "message_template", "POLITE")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.findCustomer(customerId))
        .thenReturn(Optional.of(new KhataStore.CustomerInfo(customerId, "A", "  ")));
    assertThatThrownBy(
            () ->
                service.remind(
                    owner, customerId, Map.of("channel", "SMS", "message_template", "POLITE")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.findCustomer(customerId)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.remind(
                    owner, customerId, Map.of("channel", "SMS", "message_template", "POLITE")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");

    when(store.findCustomer(customerId))
        .thenReturn(Optional.of(new KhataStore.CustomerInfo(customerId, "A", "+91")));
    when(notifications.sendKhataReminder(
            any(), any(), eq(ShareChannel.SMS), any(), any(), anyLong()))
        .thenThrow(new AppException("CHANNEL_UNAVAILABLE", "down", 503));
    assertThatThrownBy(
            () ->
                service.remind(
                    owner, customerId, Map.of("channel", "SMS", "message_template", "POLITE")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CHANNEL_UNAVAILABLE");

    when(notifications.sendKhataReminder(any(), any(), any(), any(), any(), anyLong()))
        .thenThrow(new AppException("RATE_LIMIT_EXCEEDED", "x", 429));
    assertThatThrownBy(
            () ->
                service.remind(
                    owner, customerId, Map.of("channel", "WHATSAPP", "message_template", "POLITE")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void paymentHistoryWithRowsAndBlankExport() {
    when(store.paymentHistory(any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new KhataStore.PaymentHistoryRow(
                    UUID.randomUUID(),
                    "RCPT-1",
                    LocalDate.of(2026, 7, 1),
                    "A",
                    "+91",
                    "CASH",
                    100,
                    "n",
                    0)));
    when(store.countPaymentHistory(any(), any(), any(), any(), any())).thenReturn(1L);
    when(store.paymentHistoryTotalPaise(any(), any(), any(), any(), any())).thenReturn(100L);
    KhataService.ListResult list =
        (KhataService.ListResult)
            service.paymentHistory(owner, null, null, null, null, 0, 0, "  ", null);
    assertThat(list.data().get("repayments")).asList().hasSize(1);

    MedmatePrincipal noPharmacy =
        new MedmatePrincipal(staff, AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(noPharmacy, false, null, null, 1, 20, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.list(null, false, null, null, 1, 20, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal staffUser =
        new MedmatePrincipal(staff, AuthRole.PHARMACY_STAFF, pharmacy, TokenScope.FULL, "j");
    when(store.kpi(any(), any(), any())).thenReturn(new KhataStore.KpiSnapshot(0, 0, 0, 0, 0));
    when(store.aging(any(), any())).thenReturn(new KhataStore.AgingBuckets(0, 0, 0));
    when(store.listOutstanding(any(), anyBoolean(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of());
    when(store.countOutstanding(any(), anyBoolean(), any())).thenReturn(0L);
    service.list(staffUser, false, null, null, null, null, null);
    service.list(staffUser, false, null, null, 0, 0, null);
    service.list(staffUser, false, null, null, 2, 50, null);
    service.paymentHistory(owner, null, null, "  ", "  ", null, null, null, null);
    service.paymentHistory(owner, null, null, "  ", "  ", 0, 0, null, null);
    service.paymentHistory(owner, null, null, "  ", "  ", 2, 50, null, null);
  }
}

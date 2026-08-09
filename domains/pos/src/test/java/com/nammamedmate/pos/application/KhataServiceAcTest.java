package com.nammamedmate.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pos.adapter.out.export.SimpleXlsxExporter;
import com.nammamedmate.pos.application.port.out.KhataStore;
import com.nammamedmate.pos.application.port.out.PosNotificationPort;
import com.nammamedmate.pos.application.port.out.PosPlanPort;
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
class KhataServiceAcTest {

  static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Mock KhataStore store;
  @Mock PosPlanPort plan;
  @Mock PosNotificationPort notifications;
  @Mock RateLimiter rateLimiter;

  KhataService service;
  UUID pharmacy = UUID.randomUUID();
  UUID customerId = UUID.randomUUID();
  UUID staff = UUID.randomUUID();
  MedmatePrincipal owner =
      new MedmatePrincipal(staff, AuthRole.PHARMACY_OWNER, pharmacy, TokenScope.FULL, "j");
  MedmatePrincipal staffPrincipal =
      new MedmatePrincipal(staff, AuthRole.PHARMACY_STAFF, pharmacy, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    when(plan.starterFeaturesEnabled()).thenReturn(true);
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    when(store.customerKnownToPharmacy(pharmacy, customerId)).thenReturn(true);
    service =
        new KhataService(
            store,
            plan,
            notifications,
            new SimpleXlsxExporter(),
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac_freePlanLocked() {
    when(plan.starterFeaturesEnabled()).thenReturn(false);
    assertThatThrownBy(() -> service.list(owner, false, null, null, 1, 20, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PLAN_FEATURE_LOCKED");
  }

  @Test
  void ac_repaymentCreatesReceiptAndReducesOutstanding() {
    when(store.findCustomer(customerId))
        .thenReturn(Optional.of(new KhataStore.CustomerInfo(customerId, "Ramesh", "+9198")));
    when(store.outstandingPaise(pharmacy, customerId)).thenReturn(850_000L);
    when(store.recordRepayment(
            eq(pharmacy),
            eq(customerId),
            eq(500_000L),
            eq("CASH"),
            isNull(),
            isNull(),
            eq(staff),
            any()))
        .thenReturn(
            new KhataStore.RepaymentResult(
                UUID.randomUUID(),
                "RCPT-2026-07-000013",
                "Ramesh",
                500_000L,
                "CASH",
                850_000L,
                350_000L,
                "https://cdn.medmate.in/r.pdf",
                NOW));

    Map<String, Object> data =
        service.repay(owner, customerId, Map.of("amount", 5000.00, "payment_mode", "CASH"));

    assertThat(data.get("receipt_number")).isEqualTo("RCPT-2026-07-000013");
    assertThat(data.get("previous_outstanding")).isEqualTo(new BigDecimal("8500.00"));
    assertThat(data.get("new_outstanding")).isEqualTo(new BigDecimal("3500.00"));
  }

  @Test
  void ac_repaymentExceedsOutstanding() {
    when(store.findCustomer(customerId))
        .thenReturn(Optional.of(new KhataStore.CustomerInfo(customerId, "Ramesh", "+9198")));
    when(store.outstandingPaise(pharmacy, customerId)).thenReturn(100_000L);

    assertThatThrownBy(
            () ->
                service.repay(owner, customerId, Map.of("amount", 2000.00, "payment_mode", "CASH")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REPAYMENT_EXCEEDS_OUTSTANDING");
  }

  @Test
  void ac_overdueOnlyFilters() {
    when(store.kpi(any(), any(), any()))
        .thenReturn(new KhataStore.KpiSnapshot(100, 50, 10, 20, 1000));
    when(store.aging(any(), any())).thenReturn(new KhataStore.AgingBuckets(50, 30, 20));
    when(store.listOutstanding(eq(pharmacy), eq(true), isNull(), isNull(), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new KhataStore.CustomerOutstandingRow(
                    customerId, "A", "+91", 100, LocalDate.of(2026, 6, 1), 23, true)));
    when(store.countOutstanding(eq(pharmacy), eq(true), isNull())).thenReturn(1L);

    KhataService.ListResult result = service.list(owner, true, null, null, 1, 20, null);
    assertThat(result.data().get("customers")).asList().hasSize(1);
    verify(store).listOutstanding(eq(pharmacy), eq(true), isNull(), isNull(), anyInt(), anyInt());
  }

  @Test
  void ac_crossPharmacyCustomerReturnsNotFound() {
    UUID foreignCustomer = UUID.randomUUID();
    when(store.customerKnownToPharmacy(pharmacy, foreignCustomer)).thenReturn(false);

    assertThatThrownBy(() -> service.detail(owner, foreignCustomer, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
    verify(store, never()).findCustomer(foreignCustomer);

    assertThatThrownBy(
            () ->
                service.repay(
                    owner, foreignCustomer, Map.of("amount", 100.00, "payment_mode", "CASH")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");

    assertThatThrownBy(
            () ->
                service.remind(
                    owner, foreignCustomer, Map.of("channel", "SMS", "message_template", "POLITE")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");
  }

  @Test
  void ac_ledgerReverseChronological() {
    when(store.findCustomer(customerId))
        .thenReturn(Optional.of(new KhataStore.CustomerInfo(customerId, "Ramesh", "+9198")));
    when(store.outstandingPaise(pharmacy, customerId)).thenReturn(850_000L);
    when(store.creditLimitPaise(pharmacy, customerId)).thenReturn(5_000_000L);
    when(store.unpaidBills(eq(pharmacy), eq(customerId), any())).thenReturn(List.of());
    UUID e1 = UUID.randomUUID();
    UUID e2 = UUID.randomUUID();
    when(store.ledgerDesc(pharmacy, customerId))
        .thenReturn(
            List.of(
                new KhataStore.LedgerRow(
                    e1, "DEBIT", LocalDate.of(2026, 7, 10), "INV-1", 550_000L, 850_000L, NOW),
                new KhataStore.LedgerRow(
                    e2,
                    "CREDIT",
                    LocalDate.of(2026, 7, 5),
                    "RCPT-1",
                    200_000L,
                    300_000L,
                    NOW.minusSeconds(100))));

    Map<String, Object> data = service.detail(owner, customerId, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> ledger = (List<Map<String, Object>>) data.get("ledger");
    assertThat(ledger.get(0).get("type")).isEqualTo("DEBIT");
    assertThat(ledger.get(0).get("running_balance")).isEqualTo(new BigDecimal("8500.00"));
    assertThat(ledger.get(1).get("type")).isEqualTo("CREDIT");
  }

  @Test
  void ac_reminderRateLimitedWithin24h() {
    when(store.findCustomer(customerId))
        .thenReturn(Optional.of(new KhataStore.CustomerInfo(customerId, "Ramesh", "+9198")));
    when(store.outstandingPaise(pharmacy, customerId)).thenReturn(100_000L);
    when(store.lastReminderAt(pharmacy, customerId))
        .thenReturn(Optional.of(NOW.minusSeconds(3600)));

    assertThatThrownBy(
            () ->
                service.remind(
                    owner, customerId, Map.of("channel", "WHATSAPP", "message_template", "POLITE")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REMINDER_RATE_LIMITED");
    verify(notifications, never()).sendKhataReminder(any(), any(), any(), any(), any(), anyLong());
  }

  @Test
  void ac_staffCannotRemind() {
    assertThatThrownBy(
            () ->
                service.remind(
                    staffPrincipal,
                    customerId,
                    Map.of("channel", "SMS", "message_template", "FIRM")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("STAFF_CANNOT_REMIND");
  }

  @Test
  void remindHappyPath() {
    when(store.findCustomer(customerId))
        .thenReturn(Optional.of(new KhataStore.CustomerInfo(customerId, "Ramesh", "+9198")));
    when(store.outstandingPaise(pharmacy, customerId)).thenReturn(100_000L);
    when(store.lastReminderAt(pharmacy, customerId)).thenReturn(Optional.empty());
    when(notifications.sendKhataReminder(any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(new PosNotificationPort.ShareResult("wa_msg_1", NOW));

    Map<String, Object> data =
        service.remind(
            owner, customerId, Map.of("channel", "WHATSAPP", "message_template", "POLITE"));
    assertThat(data.get("message_id")).isEqualTo("wa_msg_1");
    verify(store)
        .insertReminderLog(
            any(),
            eq(pharmacy),
            eq(customerId),
            eq("WHATSAPP"),
            eq("POLITE"),
            eq("wa_msg_1"),
            eq(NOW));
  }

  @Test
  void collectionRateUsesDivisionNotMinus() {
    when(store.kpi(any(), any(), any()))
        .thenReturn(new KhataStore.KpiSnapshot(0, 0, 480_000L, 640_000L, 1_000_000L));
    when(store.aging(any(), any())).thenReturn(new KhataStore.AgingBuckets(0, 0, 0));
    when(store.listOutstanding(any(), anyBoolean(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of());
    when(store.countOutstanding(any(), anyBoolean(), any())).thenReturn(0L);

    KhataService.ListResult result = service.list(owner, false, null, null, 1, 20, null);
    @SuppressWarnings("unchecked")
    Map<String, Object> kpi = (Map<String, Object>) result.data().get("kpi");
    assertThat(kpi.get("collection_rate_pct")).isEqualTo(new BigDecimal("75.0"));
  }

  @Test
  void paymentHistoryExportExcel() {
    when(store.paymentHistory(any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new KhataStore.PaymentHistoryRow(
                    UUID.randomUUID(),
                    "RCPT-1",
                    LocalDate.of(2026, 7, 24),
                    "A",
                    "+91",
                    "CASH",
                    100,
                    "n",
                    0)));
    Object out = service.paymentHistory(owner, null, null, null, null, 1, 20, "EXCEL", null);
    assertThat(out).isInstanceOf(KhataService.FileExport.class);
    assertThat(SimpleXlsxExporter.looksLikeXlsx(((KhataService.FileExport) out).bytes())).isTrue();
    assertThat(new KhataService.FileExport("a.xlsx", "x", null).bytes()).isEmpty();
  }
}

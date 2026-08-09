package com.nammamedmate.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.pos.adapter.out.export.SimpleXlsxExporter;
import com.nammamedmate.pos.application.port.out.InvoiceSettingsStore;
import com.nammamedmate.pos.application.port.out.InvoiceStore;
import com.nammamedmate.pos.application.port.out.PosKhataPort;
import com.nammamedmate.pos.application.port.out.PosNotificationPort;
import com.nammamedmate.pos.application.port.out.PosPharmacyPort;
import com.nammamedmate.pos.domain.Invoice;
import com.nammamedmate.pos.domain.InvoiceChannel;
import com.nammamedmate.pos.domain.InvoiceStatus;
import com.nammamedmate.pos.domain.PaymentMethod;
import com.nammamedmate.pos.domain.PaymentStatus;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesLedgerCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:15:00Z");

  @Mock InvoiceStore invoiceStore;
  @Mock InvoiceSettingsStore settingsStore;
  @Mock PosPharmacyPort pharmacyPort;
  @Mock PosNotificationPort notificationPort;
  @Mock PosKhataPort khata;

  SalesLedgerService service;
  UUID pharmacy = UUID.randomUUID();
  UUID saleId = UUID.randomUUID();
  MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, pharmacy, TokenScope.FULL, "j");
  Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  InMemoryRateLimiter rl;

  @BeforeEach
  void setUp() {
    rl = new InMemoryRateLimiter(clock);
    InvoiceService invoiceService =
        new InvoiceService(
            invoiceStore,
            settingsStore,
            pharmacyPort,
            notificationPort,
            new SimpleXlsxExporter(),
            rl,
            clock);
    service =
        new SalesLedgerService(
            invoiceStore, invoiceService, khata, new SimpleXlsxExporter(), rl, clock);
    when(invoiceStore.periodSummary(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new InvoiceStore.PeriodSummary(0, 0, 0, 0, 0));
    when(invoiceStore.countSales(any(), any(), any(), any(), any(), any(), any())).thenReturn(0L);
    when(invoiceStore.listSales(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of());
    when(invoiceStore.channelRevenue(any(), any(), any())).thenReturn(List.of());
    when(invoiceStore.paymentModeMix(any(), any(), any())).thenReturn(List.of());
    when(invoiceStore.topProducts(any(), any(), any(), anyInt())).thenReturn(List.of());
  }

  @Test
  void validationAndAdminBranches() {
    assertThatThrownBy(
            () ->
                service.list(
                    owner, null, null, null, null, null, null, "bad", null, 1, 20, null, null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.list(
                    owner,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "date",
                    "sideways",
                    1,
                    20,
                    null,
                    null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.list(
                    owner, null, null, null, null, null, null, null, null, 1, 20, null, "2025-25",
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.list(
                    owner, null, null, null, null, null, null, null, null, 1, 20, null, "bad",
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.list(
                    owner,
                    LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 7, 1),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    20,
                    null,
                    null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.list(
                    owner,
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 10),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    20,
                    "CSV",
                    null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    MedmatePrincipal admin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () ->
                service.list(
                    admin, null, null, null, null, null, null, null, null, 1, 20, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    service.list(
        admin, null, null, null, null, null, null, null, null, 1, 20, null, null, pharmacy);

    MedmatePrincipal compliance =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    service.summary(compliance, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 24), pharmacy);

    assertThatThrownBy(
            () ->
                service.list(
                    null, null, null, null, null, null, null, null, null, 1, 20, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () ->
                service.list(
                    customer, null, null, null, null, null, null, null, null, 1, 20, null, null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    MedmatePrincipal staffNoPh =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () ->
                service.list(
                    staffNoPh, null, null, null, null, null, null, null, null, 1, 20, null, null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    assertThat(new SalesLedgerService.ListResult(null, PaginationMeta.of(1, 20, 0)).data())
        .isEmpty();
  }

  @Test
  void markPaidCashAndValidationEdges() {
    Invoice pending = invoice(PaymentStatus.PENDING, PaymentMethod.CASH, 10_000L, 0L);
    when(invoiceStore.findById(pharmacy, saleId)).thenReturn(Optional.of(pending));
    when(invoiceStore.nextSequence(eq(pharmacy), anyInt(), anyInt())).thenReturn(1);

    Map<String, Object> paid =
        service.markPaid(
            owner,
            saleId,
            Map.of(
                "payment_mode",
                "cash",
                "amount",
                new BigDecimal("100.00"),
                "reference_number",
                "NEFT1",
                "note",
                "ok"));
    assertThat(paid.get("receipt_number").toString()).startsWith("RCPT-");
    verify(invoiceStore)
        .markPaid(
            eq(pharmacy), eq(saleId), eq(PaymentStatus.PAID), eq("NEFT1"), eq(10_000L), any());

    assertThatThrownBy(() -> service.markPaid(null, saleId, Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(
            () -> service.markPaid(owner, null, Map.of("payment_mode", "CASH", "amount", 100)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SALE_NOT_FOUND");
    assertThatThrownBy(() -> service.markPaid(owner, saleId, Map.of("amount", 100)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.markPaid(owner, saleId, Map.of("payment_mode", "COD", "amount", 100)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.markPaid(owner, saleId, Map.of("payment_mode", "CASH", "amount", 0)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.markPaid(
                    owner,
                    saleId,
                    Map.of(
                        "payment_mode", "CASH", "amount", 100, "reference_number", "x".repeat(51))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.markPaid(
                    owner,
                    saleId,
                    Map.of("payment_mode", "CASH", "amount", 100, "note", "n".repeat(301))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.markPaid(owner, saleId, Map.of("payment_mode", "CASH", "amount", "nope")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    MedmatePrincipal ownerNoPh =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () ->
                service.markPaid(ownerNoPh, saleId, Map.of("payment_mode", "CASH", "amount", 100)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void creditKhataBlankReceiptFallsBackAndSummaryMix() {
    Invoice credit = invoice(PaymentStatus.PARTIAL, PaymentMethod.CREDIT, 20_000L, 5_000L);
    when(invoiceStore.findById(pharmacy, saleId)).thenReturn(Optional.of(credit));
    when(invoiceStore.nextSequence(eq(pharmacy), anyInt(), anyInt())).thenReturn(9);
    when(khata.recordCreditRepayment(
            any(), any(), any(Long.class), any(), any(), any(), any(), any()))
        .thenReturn("  ");

    Map<String, Object> data =
        service.markPaid(owner, saleId, Map.of("payment_mode", "CARD", "amount", 150));
    assertThat(data.get("receipt_number").toString()).contains("000009");

    when(invoiceStore.channelRevenue(eq(pharmacy), any(), any()))
        .thenReturn(List.of(new InvoiceStore.ChannelAgg("ONLINE", 100L)));
    when(invoiceStore.paymentModeMix(eq(pharmacy), any(), any()))
        .thenReturn(List.of(new InvoiceStore.PaymentModeAgg("UPI", 2, 500L)));
    when(invoiceStore.topProducts(eq(pharmacy), any(), any(), eq(10)))
        .thenReturn(List.of(new InvoiceStore.ProductAgg("Para", 500L, 3)));
    when(invoiceStore.periodSummary(eq(pharmacy), any(), any(), any(), any(), any(), any()))
        .thenReturn(new InvoiceStore.PeriodSummary(2, 3, 500L, 50L, 0));

    Map<String, Object> summary =
        service.summary(owner, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 24), null);
    assertThat(summary.get("total_bills")).isEqualTo(2L);
    @SuppressWarnings("unchecked")
    Map<String, Object> mix = (Map<String, Object>) summary.get("payment_mode_mix");
    assertThat(mix).containsKey("UPI");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> products =
        (List<Map<String, Object>>) summary.get("top_selling_products");
    assertThat(products).hasSize(1);

    when(invoiceStore.channelRevenue(eq(pharmacy), any(), any())).thenReturn(List.of());
    Map<String, Object> empty = service.summary(owner, null, null, null);
    @SuppressWarnings("unchecked")
    Map<String, Object> ovc = (Map<String, Object>) empty.get("online_vs_counter");
    assertThat(((BigDecimal) ovc.get("online_pct")).add((BigDecimal) ovc.get("counter_pct")))
        .isEqualByComparingTo("100.0");
  }

  @Test
  void remainingBranchCoverage() {
    // null page/limit defaults + blank sort/order clamps + blank export skipped
    service.list(
        owner,
        LocalDate.of(2026, 7, 1),
        LocalDate.of(2026, 7, 24),
        " ",
        " ",
        " ",
        null,
        " ",
        " ",
        null,
        null,
        " ",
        "",
        null);
    service.list(
        owner,
        LocalDate.of(2026, 7, 1),
        LocalDate.of(2026, 7, 24),
        null,
        null,
        null,
        null,
        null,
        null,
        0,
        0,
        null,
        null,
        null);
    service.list(
        owner,
        LocalDate.of(2026, 7, 1),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        1,
        200,
        null,
        null,
        null);

    when(invoiceStore.channelRevenue(eq(pharmacy), any(), any()))
        .thenReturn(List.of(new InvoiceStore.ChannelAgg("OTHER", 50L)));
    service.summary(owner, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 24), null);

    Invoice pending = invoice(PaymentStatus.PENDING, PaymentMethod.CREDIT, 10_000L, 0L);
    when(invoiceStore.findById(pharmacy, saleId)).thenReturn(Optional.of(pending));
    when(invoiceStore.nextSequence(eq(pharmacy), anyInt(), anyInt())).thenReturn(2);
    when(khata.recordCreditRepayment(
            any(), any(), any(Long.class), any(), any(), any(), any(), any()))
        .thenReturn(null);

    Map<String, Object> body = new java.util.HashMap<>();
    body.put("payment_mode", "UPI");
    body.put("amount", "100.00");
    body.put("reference_number", " ");
    body.put("note", "");
    assertThat(service.markPaid(owner, saleId, body).get("receipt_number").toString())
        .startsWith("RCPT-");

    body.put("amount", null);
    assertThatThrownBy(() -> service.markPaid(owner, saleId, body))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.markPaid(owner, saleId, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    Invoice cash = invoice(PaymentStatus.PENDING, PaymentMethod.CASH, 10_000L, 0L);
    // null customer name for PDF nullToEmpty
    cash =
        new Invoice(
            saleId,
            pharmacy,
            "INV-2026-07-000099",
            null,
            InvoiceChannel.COUNTER,
            null,
            null,
            null,
            null,
            10_000L,
            0,
            10,
            10_000L,
            PaymentMethod.CASH,
            PaymentStatus.PAID,
            null,
            10_000L,
            0,
            0,
            InvoiceStatus.ACTIVE,
            null,
            NOW);
    when(invoiceStore.listSales(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(new InvoiceStore.InvoiceListRow(cash, 1)));
    Object pdf =
        service.list(
            owner,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 10),
            null,
            null,
            null,
            null,
            null,
            null,
            1,
            20,
            "PDF",
            null,
            null);
    assertThat(((InvoiceService.FileExport) pdf).bytes().length).isPositive();
  }

  @Test
  void rateLimitAndListFilters() {
    AtomicInteger hits = new AtomicInteger();
    InMemoryRateLimiter tight = new InMemoryRateLimiter(clock);
    InvoiceService invoiceService =
        new InvoiceService(
            invoiceStore,
            settingsStore,
            pharmacyPort,
            notificationPort,
            new SimpleXlsxExporter(),
            tight,
            clock);
    SalesLedgerService limited =
        new SalesLedgerService(
            invoiceStore, invoiceService, khata, new SimpleXlsxExporter(), tight, clock);

    when(invoiceStore.listSales(
            eq(pharmacy),
            any(),
            any(),
            eq("CASH"),
            eq("PAID"),
            eq("COUNTER"),
            eq("Priya"),
            eq("amount"),
            eq("asc"),
            anyInt(),
            anyInt()))
        .thenReturn(
            List.of(
                new InvoiceStore.InvoiceListRow(
                    invoice(PaymentStatus.PAID, PaymentMethod.CASH, 100L, 100L), 1)));
    when(invoiceStore.periodSummary(
            eq(pharmacy), any(), any(), eq("CASH"), eq("PAID"), eq("COUNTER"), eq("Priya")))
        .thenReturn(new InvoiceStore.PeriodSummary(1, 1, 100, 10, 0));
    when(invoiceStore.countSales(
            eq(pharmacy), any(), any(), eq("CASH"), eq("PAID"), eq("COUNTER"), eq("Priya")))
        .thenReturn(1L);

    SalesLedgerService.ListResult page =
        (SalesLedgerService.ListResult)
            limited.list(
                owner,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 24),
                "counter",
                "cash",
                "paid",
                "Priya",
                "amount",
                "asc",
                1,
                20,
                null,
                null,
                null);
    assertThat(page.data().get("sales")).asList().hasSize(1);
    hits.incrementAndGet();

    // 59 more succeeds (limit 60); 61st fails
    for (int i = 0; i < 59; i++) {
      limited.list(
          owner,
          LocalDate.of(2026, 7, 1),
          LocalDate.of(2026, 7, 24),
          null,
          null,
          null,
          null,
          null,
          null,
          1,
          20,
          null,
          null,
          null);
    }
    assertThatThrownBy(
            () ->
                limited.list(
                    owner,
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 24),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    20,
                    null,
                    null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  private Invoice invoice(PaymentStatus status, PaymentMethod method, long grand, long paid) {
    return new Invoice(
        saleId,
        pharmacy,
        "INV-2026-07-000001",
        null,
        InvoiceChannel.COUNTER,
        UUID.randomUUID(),
        "Priya",
        "+91",
        null,
        grand,
        0,
        10,
        grand,
        method,
        status,
        null,
        paid,
        0,
        0,
        InvoiceStatus.ACTIVE,
        null,
        NOW);
  }
}

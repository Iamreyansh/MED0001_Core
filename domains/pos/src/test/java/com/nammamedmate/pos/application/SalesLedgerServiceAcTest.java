package com.nammamedmate.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesLedgerServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:15:00Z");

  @Mock InvoiceStore invoiceStore;
  @Mock InvoiceSettingsStore settingsStore;
  @Mock PosPharmacyPort pharmacyPort;
  @Mock PosNotificationPort notificationPort;
  @Mock PosKhataPort khata;

  SalesLedgerService service;
  InvoiceService invoiceService;
  UUID pharmacy = UUID.randomUUID();
  UUID saleId = UUID.randomUUID();
  MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, pharmacy, TokenScope.FULL, "j");
  MedmatePrincipal staff =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_STAFF, pharmacy, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    InMemoryRateLimiter rl = new InMemoryRateLimiter(clock);
    invoiceService =
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
  }

  @Test
  void ac_periodSummaryBillCountMatchesRange() {
    when(invoiceStore.periodSummary(
            eq(pharmacy),
            eq(LocalDate.of(2026, 7, 1)),
            eq(LocalDate.of(2026, 7, 24)),
            isNull(),
            isNull(),
            isNull(),
            isNull()))
        .thenReturn(
            new InvoiceStore.PeriodSummary(486, 12400, 24_860_000L, 2_245_000L, 1_540_000L));
    when(invoiceStore.countSales(
            eq(pharmacy),
            eq(LocalDate.of(2026, 7, 1)),
            eq(LocalDate.of(2026, 7, 24)),
            isNull(),
            isNull(),
            isNull(),
            isNull()))
        .thenReturn(486L);
    when(invoiceStore.listSales(
            eq(pharmacy),
            eq(LocalDate.of(2026, 7, 1)),
            eq(LocalDate.of(2026, 7, 24)),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            eq("date"),
            eq("desc"),
            anyInt(),
            anyInt()))
        .thenReturn(List.of());

    SalesLedgerService.ListResult result =
        (SalesLedgerService.ListResult)
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
                1,
                20,
                null,
                null,
                null);

    @SuppressWarnings("unchecked")
    Map<String, Object> period = (Map<String, Object>) result.data().get("period_summary");
    assertThat(period.get("bill_count")).isEqualTo(486L);
    assertThat(period.get("from_date")).isEqualTo("2026-07-01");
    assertThat(period.get("to_date")).isEqualTo("2026-07-24");
    assertThat(result.meta().total()).isEqualTo(486L);
  }

  @Test
  void ac_exportExcelTwelveMonthFyOk() {
    when(invoiceStore.listSales(
            eq(pharmacy),
            eq(LocalDate.of(2025, 4, 1)),
            eq(LocalDate.of(2026, 3, 31)),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            eq("date"),
            eq("desc"),
            eq(10_000),
            eq(0)))
        .thenReturn(List.of(new InvoiceStore.InvoiceListRow(sampleInvoice(PaymentStatus.PAID), 2)));

    Object result =
        service.list(
            owner,
            LocalDate.of(2025, 4, 1),
            LocalDate.of(2026, 3, 31),
            null,
            null,
            null,
            null,
            null,
            null,
            1,
            20,
            "EXCEL",
            null,
            null);

    assertThat(result).isInstanceOf(InvoiceService.FileExport.class);
    InvoiceService.FileExport file = (InvoiceService.FileExport) result;
    assertThat(file.filename()).isEqualTo("sales.xlsx");
    assertThat(SimpleXlsxExporter.looksLikeXlsx(file.bytes())).isTrue();
  }

  @Test
  void ac_exportRangeTooLarge() {
    assertThatThrownBy(
            () ->
                service.list(
                    owner,
                    LocalDate.of(2024, 1, 1),
                    LocalDate.of(2026, 1, 1),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    20,
                    "EXCEL",
                    null,
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EXPORT_RANGE_TOO_LARGE");
  }

  @Test
  void ac_financialYearSetsAprMar() {
    when(invoiceStore.periodSummary(
            eq(pharmacy),
            eq(LocalDate.of(2025, 4, 1)),
            eq(LocalDate.of(2026, 3, 31)),
            isNull(),
            isNull(),
            isNull(),
            isNull()))
        .thenReturn(new InvoiceStore.PeriodSummary(1, 1, 100, 10, 0));
    when(invoiceStore.countSales(any(), any(), any(), any(), any(), any(), any())).thenReturn(1L);
    when(invoiceStore.listSales(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of());

    SalesLedgerService.ListResult result =
        (SalesLedgerService.ListResult)
            service.list(
                owner, null, null, null, null, null, null, null, null, 1, 20, null, "2025-26",
                null);

    @SuppressWarnings("unchecked")
    Map<String, Object> period = (Map<String, Object>) result.data().get("period_summary");
    assertThat(period.get("from_date")).isEqualTo("2025-04-01");
    assertThat(period.get("to_date")).isEqualTo("2026-03-31");
  }

  @Test
  void ac_markPaidCreditCreatesReceiptAndSetsPaid() {
    Invoice pending = sampleInvoice(PaymentStatus.PENDING, PaymentMethod.CREDIT, 45_000L, 0L);
    when(invoiceStore.findById(pharmacy, saleId)).thenReturn(Optional.of(pending));
    when(invoiceStore.nextSequence(eq(pharmacy), anyInt(), anyInt())).thenReturn(14);
    when(khata.recordCreditRepayment(
            any(), eq(saleId), eq(45_000L), eq(pharmacy), eq("UPI"), isNull(), isNull(), any()))
        .thenReturn("RCPT-2026-07-000014");

    Map<String, Object> data =
        service.markPaid(owner, saleId, Map.of("payment_mode", "UPI", "amount", 450.00));

    assertThat(data.get("previous_payment_status")).isEqualTo("PENDING");
    assertThat(data.get("new_payment_status")).isEqualTo("PAID");
    assertThat(data.get("receipt_number")).isEqualTo("RCPT-2026-07-000014");
    verify(invoiceStore)
        .markPaid(
            eq(pharmacy),
            eq(saleId),
            eq(PaymentStatus.PAID),
            eq("RCPT-2026-07-000014"),
            eq(45_000L),
            any());
    verify(khata)
        .recordCreditRepayment(
            any(), eq(saleId), eq(45_000L), eq(pharmacy), eq("UPI"), isNull(), isNull(), any());
  }

  @Test
  void ac_markPaidAlreadyPaidRejected() {
    when(invoiceStore.findById(pharmacy, saleId))
        .thenReturn(Optional.of(sampleInvoice(PaymentStatus.PAID)));

    assertThatThrownBy(
            () -> service.markPaid(owner, saleId, Map.of("payment_mode", "CASH", "amount", 100)))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SALE_ALREADY_PAID");
  }

  @Test
  void ac_summaryOnlineVsCounterPctSum100() {
    when(invoiceStore.periodSummary(eq(pharmacy), any(), any(), any(), any(), any(), any()))
        .thenReturn(new InvoiceStore.PeriodSummary(10, 20, 248_600_00L, 0, 0));
    when(invoiceStore.channelRevenue(eq(pharmacy), any(), any()))
        .thenReturn(
            List.of(
                new InvoiceStore.ChannelAgg("ONLINE", 62_000_00L),
                new InvoiceStore.ChannelAgg("COUNTER", 186_600_00L)));
    when(invoiceStore.paymentModeMix(eq(pharmacy), any(), any())).thenReturn(List.of());
    when(invoiceStore.topProducts(eq(pharmacy), any(), any(), eq(10))).thenReturn(List.of());

    Map<String, Object> data =
        service.summary(owner, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 24), null);

    @SuppressWarnings("unchecked")
    Map<String, Object> ovc = (Map<String, Object>) data.get("online_vs_counter");
    BigDecimal onlinePct = (BigDecimal) ovc.get("online_pct");
    BigDecimal counterPct = (BigDecimal) ovc.get("counter_pct");
    assertThat(onlinePct.add(counterPct)).isEqualByComparingTo("100.0");
  }

  @Test
  void ac_listAlwaysIncludesPeriodSummaryDefaults() {
    // NOW is 2026-07-24 UTC → IST 2026-07-24 17:45 → month start 2026-07-01
    when(invoiceStore.periodSummary(
            eq(pharmacy),
            eq(LocalDate.of(2026, 7, 1)),
            eq(LocalDate.of(2026, 7, 24)),
            isNull(),
            isNull(),
            isNull(),
            isNull()))
        .thenReturn(new InvoiceStore.PeriodSummary(3, 5, 900, 90, 0));
    when(invoiceStore.countSales(any(), any(), any(), any(), any(), any(), any())).thenReturn(3L);
    when(invoiceStore.listSales(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of());

    SalesLedgerService.ListResult result =
        (SalesLedgerService.ListResult)
            service.list(
                owner, null, null, null, null, null, null, null, null, 2, 1, null, null, null);

    assertThat(result.data()).containsKey("period_summary");
    @SuppressWarnings("unchecked")
    Map<String, Object> period = (Map<String, Object>) result.data().get("period_summary");
    assertThat(period.get("bill_count")).isEqualTo(3L);
    assertThat(result.meta().page()).isEqualTo(2);
  }

  @Test
  void markPaidStaffForbiddenAndAmountMismatchAndNotFound() {
    assertThatThrownBy(
            () -> service.markPaid(staff, saleId, Map.of("payment_mode", "CASH", "amount", 10)))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("STAFF_CANNOT_MARK_PAID");
              assertThat(app.httpStatus()).isEqualTo(403);
            });

    when(invoiceStore.findById(pharmacy, saleId))
        .thenReturn(
            Optional.of(sampleInvoice(PaymentStatus.PENDING, PaymentMethod.CASH, 10_000L, 0L)));
    assertThatThrownBy(
            () -> service.markPaid(owner, saleId, Map.of("payment_mode", "CASH", "amount", 50)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("AMOUNT_MISMATCH");

    when(invoiceStore.findById(pharmacy, saleId)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> service.markPaid(owner, saleId, Map.of("payment_mode", "CASH", "amount", 100)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SALE_NOT_FOUND");
  }

  @Test
  void exportPdfAndDetailAlias() {
    when(invoiceStore.listSales(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(new InvoiceStore.InvoiceListRow(sampleInvoice(PaymentStatus.PAID), 1)));
    Object pdf =
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
            1,
            20,
            "PDF",
            null,
            null);
    assertThat(((InvoiceService.FileExport) pdf).contentType()).isEqualTo("application/pdf");
    assertThat(((InvoiceService.FileExport) pdf).bytes().length).isPositive();

    when(invoiceStore.findById(pharmacy, saleId))
        .thenReturn(Optional.of(sampleInvoice(PaymentStatus.PAID)));
    when(invoiceStore.listItems(saleId)).thenReturn(List.of());
    when(settingsStore.getOrCreate(pharmacy))
        .thenReturn(
            new com.nammamedmate.pos.domain.InvoiceSettings(
                pharmacy,
                com.nammamedmate.pos.domain.InvoiceTemplate.MODERN,
                "#2563EB",
                null,
                null,
                "Tax Invoice",
                "INV",
                "Authorized Signatory",
                Map.of(),
                null,
                null,
                true,
                true,
                true,
                false,
                NOW));
    when(pharmacyPort.findById(pharmacy)).thenReturn(Optional.empty());
    assertThat(service.getDetail(owner, saleId).get("invoice_id")).isEqualTo(saleId.toString());
  }

  private Invoice sampleInvoice(PaymentStatus status) {
    return sampleInvoice(status, PaymentMethod.CASH, 10_000L, 10_000L);
  }

  private Invoice sampleInvoice(PaymentStatus status, PaymentMethod method, long grand, long paid) {
    return new Invoice(
        saleId,
        pharmacy,
        "INV-2026-07-000042",
        null,
        InvoiceChannel.COUNTER,
        UUID.randomUUID(),
        "Priya Sharma",
        "+919876000001",
        null,
        grand,
        0,
        482,
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

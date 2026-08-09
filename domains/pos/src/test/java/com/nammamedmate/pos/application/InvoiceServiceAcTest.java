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
import com.nammamedmate.pos.adapter.out.export.SimplePdfExporter;
import com.nammamedmate.pos.adapter.out.export.SimpleXlsxExporter;
import com.nammamedmate.pos.application.port.out.InvoiceSettingsStore;
import com.nammamedmate.pos.application.port.out.InvoiceStore;
import com.nammamedmate.pos.application.port.out.PosNotificationPort;
import com.nammamedmate.pos.application.port.out.PosPharmacyPort;
import com.nammamedmate.pos.domain.Invoice;
import com.nammamedmate.pos.domain.InvoiceChannel;
import com.nammamedmate.pos.domain.InvoiceItem;
import com.nammamedmate.pos.domain.InvoiceSettings;
import com.nammamedmate.pos.domain.InvoiceStatus;
import com.nammamedmate.pos.domain.InvoiceTemplate;
import com.nammamedmate.pos.domain.MoneyMath;
import com.nammamedmate.pos.domain.PaymentMethod;
import com.nammamedmate.pos.domain.PaymentStatus;
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
import java.util.concurrent.atomic.AtomicInteger;
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
class InvoiceServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:15:00Z");

  @Mock InvoiceStore invoiceStore;
  @Mock InvoiceSettingsStore settingsStore;
  @Mock PosPharmacyPort pharmacyPort;
  @Mock PosNotificationPort notificationPort;

  InvoiceService service;
  UUID pharmacy = UUID.randomUUID();
  UUID staff = UUID.randomUUID();
  UUID invoiceId = UUID.randomUUID();
  MedmatePrincipal owner =
      new MedmatePrincipal(staff, AuthRole.PHARMACY_OWNER, pharmacy, TokenScope.FULL, "j");
  AtomicReference<InvoiceSettings> settingsRef = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    service =
        new InvoiceService(
            invoiceStore,
            settingsStore,
            pharmacyPort,
            notificationPort,
            new SimpleXlsxExporter(),
            new InMemoryRateLimiter(clock),
            clock);
    settingsRef.set(defaults("INV", true));
    when(settingsStore.getOrCreate(pharmacy)).thenAnswer(inv -> settingsRef.get());
    when(settingsStore.upsert(any()))
        .thenAnswer(
            inv -> {
              InvoiceSettings s = inv.getArgument(0);
              settingsRef.set(s);
              return s;
            });
    when(pharmacyPort.findById(pharmacy))
        .thenReturn(
            Optional.of(
                new PosPharmacyPort.PharmacyInfo(
                    "Balaji Medical Store",
                    "Shop 4, MG Road",
                    "+918022334455",
                    "29AABCB1234A1Z5",
                    "DL-KA-2020-00456")));
  }

  @Test
  void ac_gstBreakdownCgStSgStHalfSplit() {
    Invoice invoice = sampleInvoice(2400L);
    when(invoiceStore.findById(pharmacy, invoiceId)).thenReturn(Optional.of(invoice));
    when(invoiceStore.listItems(invoiceId))
        .thenReturn(
            List.of(
                item("Para", "30049099", 12, 4500, 482), item("Cough", "30049015", 5, 1000, 48)));

    Map<String, Object> detail = service.getDetail(owner, invoiceId);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> gst = (List<Map<String, Object>>) detail.get("gst_breakdown");
    assertThat(gst).hasSize(2);
    Map<String, Object> twelve = gst.getFirst();
    assertThat(twelve.get("slab")).isEqualTo("12%");
    assertThat(twelve.get("hsn_code")).isEqualTo("30049099");
    BigDecimal cgst = (BigDecimal) twelve.get("cgst");
    BigDecimal sgst = (BigDecimal) twelve.get("sgst");
    assertThat(cgst.add(sgst)).isEqualByComparingTo(MoneyMath.paiseToRupees(482));
    assertThat(cgst).isEqualByComparingTo(sgst);
  }

  @Test
  void ac_prefixUpdateSucceedsAndNextCheckoutUsesIt() {
    Map<String, Object> patched = service.patchSettings(owner, Map.of("invoice_prefix", "PHARM1"));
    assertThat(patched.get("invoice_prefix")).isEqualTo("PHARM1");
    assertThat(settingsRef.get().invoicePrefix()).isEqualTo("PHARM1");

    // Simulate checkout reading settings prefix (same store path as PosCheckoutService)
    when(invoiceStore.getOrCreateSettings(pharmacy))
        .thenReturn(new InvoiceStore.InvoiceSettingsRow(settingsRef.get().invoicePrefix()));
    AtomicInteger seq = new AtomicInteger(0);
    when(invoiceStore.nextSequence(eq(pharmacy), anyInt(), anyInt()))
        .thenAnswer(inv -> seq.incrementAndGet());
    var settings = invoiceStore.getOrCreateSettings(pharmacy);
    int n = invoiceStore.nextSequence(pharmacy, 2026, 7);
    String number = String.format("%s-%04d-%02d-%06d", settings.invoicePrefix(), 2026, 7, n);
    assertThat(number).isEqualTo("PHARM1-2026-07-000001");
  }

  @Test
  void ac_prefixTooLongRejected() {
    assertThatThrownBy(
            () -> service.patchSettings(owner, Map.of("invoice_prefix", "TOOLONGPREFIX")))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PREFIX_FORMAT");
  }

  @Test
  void ac_thermalPdfReturned() {
    Invoice invoice = sampleInvoice(100L);
    when(invoiceStore.findById(pharmacy, invoiceId)).thenReturn(Optional.of(invoice));
    when(invoiceStore.listItems(invoiceId))
        .thenReturn(List.of(item("Para", "30049099", 12, 100, 11)));

    InvoiceService.FileExport pdf = service.pdf(owner, invoiceId, "THERMAL");
    assertThat(pdf.contentType()).isEqualTo("application/pdf");
    assertThat(pdf.filename()).endsWith(".pdf");
    assertThat(pdf.bytes()[0]).isEqualTo((byte) '%');
    assertThat(new String(pdf.bytes()))
        .contains("/MediaBox [0 0 " + SimplePdfExporter.THERMAL_WIDTH);
  }

  @Test
  void ac_whatsappShareRecordsSentAt() {
    Invoice invoice = sampleInvoice(100L);
    when(invoiceStore.findById(pharmacy, invoiceId)).thenReturn(Optional.of(invoice));
    when(invoiceStore.listItems(invoiceId)).thenReturn(List.of());
    when(notificationPort.shareInvoice(
            eq(pharmacy),
            eq(invoiceId),
            any(),
            eq(ShareChannel.WHATSAPP),
            eq("+919876000001"),
            any()))
        .thenReturn(new PosNotificationPort.ShareResult("wa_msg_1", NOW));

    Map<String, Object> result = service.share(owner, invoiceId, "WHATSAPP", "+919876000001");
    assertThat(result.get("channel")).isEqualTo("WHATSAPP");
    assertThat(result.get("sent_at")).isEqualTo(NOW.toString());
    assertThat(result.get("message_id")).isEqualTo("wa_msg_1");
    verify(notificationPort)
        .shareInvoice(
            eq(pharmacy),
            eq(invoiceId),
            any(),
            eq(ShareChannel.WHATSAPP),
            eq("+919876000001"),
            any());
  }

  @Test
  void ac_invoiceNumberSequentialNoSkip() {
    AtomicInteger seq = new AtomicInteger(41);
    when(invoiceStore.nextSequence(eq(pharmacy), eq(2026), eq(7)))
        .thenAnswer(inv -> seq.incrementAndGet());
    when(invoiceStore.getOrCreateSettings(pharmacy))
        .thenReturn(new InvoiceStore.InvoiceSettingsRow("INV"));
    int a = invoiceStore.nextSequence(pharmacy, 2026, 7);
    int b = invoiceStore.nextSequence(pharmacy, 2026, 7);
    assertThat(a).isEqualTo(42);
    assertThat(b).isEqualTo(43);
    assertThat(b - a).isEqualTo(1);
  }

  @Test
  void ac_exportExcelDownloadable() {
    Invoice invoice = sampleInvoice(45000L);
    when(invoiceStore.list(
            eq(pharmacy), isNull(), isNull(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
        .thenReturn(List.of(new InvoiceStore.InvoiceListRow(invoice, 3)));

    Object result = service.list(owner, null, null, null, null, null, 1, 20, "EXCEL", null);
    assertThat(result).isInstanceOf(InvoiceService.FileExport.class);
    InvoiceService.FileExport file = (InvoiceService.FileExport) result;
    assertThat(file.filename()).endsWith(".xlsx");
    assertThat(SimpleXlsxExporter.looksLikeXlsx(file.bytes())).isTrue();
  }

  @Test
  void ac_showMrpSavingsOnDetailAndPdf() {
    settingsRef.set(defaults("INV", true));
    Invoice invoice =
        new Invoice(
            invoiceId,
            pharmacy,
            "INV-2026-07-000042",
            null,
            InvoiceChannel.COUNTER,
            null,
            "Priya",
            "+919876000001",
            null,
            45000,
            0,
            4821,
            45000,
            PaymentMethod.CASH,
            PaymentStatus.PAID,
            null,
            45000,
            0,
            2400,
            InvoiceStatus.ACTIVE,
            null,
            NOW);
    when(invoiceStore.findById(pharmacy, invoiceId)).thenReturn(Optional.of(invoice));
    when(invoiceStore.listItems(invoiceId)).thenReturn(List.of());

    Map<String, Object> detail = service.getDetail(owner, invoiceId);
    assertThat(detail.get("mrp_savings")).isEqualTo(MoneyMath.paiseToRupees(2400));

    InvoiceService.FileExport pdf = service.pdf(owner, invoiceId, "MODERN");
    assertThat(new String(pdf.bytes())).contains("You saved Rs");
  }

  private InvoiceSettings defaults(String prefix, boolean showMrp) {
    return new InvoiceSettings(
        pharmacy,
        InvoiceTemplate.MODERN,
        "#2563EB",
        null,
        null,
        "Tax Invoice",
        prefix,
        "Authorized Signatory",
        null,
        null,
        null,
        showMrp,
        true,
        true,
        false,
        NOW);
  }

  private Invoice sampleInvoice(long mrpSavings) {
    return new Invoice(
        invoiceId,
        pharmacy,
        "INV-2026-07-000042",
        null,
        InvoiceChannel.COUNTER,
        null,
        "Priya",
        "+919876000001",
        "Dr. Ramesh",
        45000,
        0,
        4821,
        45000,
        PaymentMethod.CASH,
        PaymentStatus.PAID,
        null,
        45000,
        0,
        mrpSavings,
        InvoiceStatus.ACTIVE,
        null,
        NOW);
  }

  private InvoiceItem item(String name, String hsn, int gst, long total, long gstAmt) {
    return new InvoiceItem(
        UUID.randomUUID(),
        invoiceId,
        UUID.randomUUID(),
        name,
        hsn,
        UUID.randomUUID(),
        "BN1",
        LocalDate.of(2027, 6, 30),
        15,
        2,
        false,
        total / 2,
        gst,
        total - gstAmt,
        gstAmt,
        total,
        false,
        NOW);
  }
}

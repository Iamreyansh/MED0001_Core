package com.nammamedmate.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
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
import com.nammamedmate.pos.domain.PaymentMethod;
import com.nammamedmate.pos.domain.PaymentStatus;
import com.nammamedmate.pos.domain.ShareChannel;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
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
class InvoiceServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:15:00Z");

  @Mock InvoiceStore invoiceStore;
  @Mock InvoiceSettingsStore settingsStore;
  @Mock PosPharmacyPort pharmacyPort;
  @Mock PosNotificationPort notificationPort;
  @Mock RateLimiter rateLimiter;

  InvoiceService service;
  UUID pharmacy = UUID.randomUUID();
  UUID invoiceId = UUID.randomUUID();
  MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, pharmacy, TokenScope.FULL, "j");
  MedmatePrincipal staff =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_STAFF, pharmacy, TokenScope.FULL, "j");
  InvoiceSettings settings;

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
            rateLimiter,
            clock);
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    settings =
        new InvoiceSettings(
            pharmacy,
            InvoiceTemplate.MINIMAL,
            "#112233",
            "https://logo",
            null,
            "Tax Invoice",
            "INV",
            "Sign",
            Map.of("bank_name", "HDFC"),
            "terms",
            "thanks",
            false,
            false,
            false,
            true,
            NOW);
    when(settingsStore.getOrCreate(pharmacy)).thenReturn(settings);
    when(settingsStore.upsert(any())).thenAnswer(inv -> inv.getArgument(0));
    when(pharmacyPort.findById(pharmacy)).thenReturn(Optional.empty());
  }

  @Test
  void listPaginationAndFiltersAndPdfExport() {
    Invoice inv = invoice();
    when(invoiceStore.count(eq(pharmacy), any(), any(), any(), any(), any())).thenReturn(1L);
    when(invoiceStore.list(eq(pharmacy), any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of(new InvoiceStore.InvoiceListRow(inv, 1)));

    InvoiceService.ListResult page =
        (InvoiceService.ListResult)
            service.list(
                owner,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                "cash",
                "counter",
                "Priya",
                0,
                0,
                null,
                null);
    assertThat(page.meta().total()).isEqualTo(1);
    assertThat(page.data().get("invoices")).asList().hasSize(1);

    InvoiceService.ListResult page2 =
        (InvoiceService.ListResult)
            service.list(owner, null, null, " ", " ", " ", 2, 150, "  ", null);
    assertThat(page2.meta().limit()).isEqualTo(100);
    assertThat(page2.meta().page()).isEqualTo(2);

    InvoiceService.ListResult page3 =
        (InvoiceService.ListResult)
            service.list(owner, null, null, null, null, null, null, null, null, null);
    assertThat(page3.meta().page()).isEqualTo(1);
    assertThat(page3.meta().limit()).isEqualTo(20);

    InvoiceService.FileExport pdf =
        (InvoiceService.FileExport)
            service.list(owner, null, null, null, null, null, 1, 20, "PDF", null);
    assertThat(pdf.contentType()).isEqualTo("application/pdf");
    assertThat(pdf.bytes()[0]).isEqualTo((byte) '%');

    assertThatThrownBy(() -> service.list(owner, null, null, null, null, null, 1, 20, "CSV", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void detailNotFoundAndSettingsFlags() {
    when(invoiceStore.findById(pharmacy, invoiceId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getDetail(owner, invoiceId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVOICE_NOT_FOUND");
    assertThatThrownBy(() -> service.getDetail(owner, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVOICE_NOT_FOUND");

    when(invoiceStore.findById(pharmacy, invoiceId)).thenReturn(Optional.of(invoice()));
    when(invoiceStore.listItems(invoiceId))
        .thenReturn(
            List.of(
                new InvoiceItem(
                    UUID.randomUUID(),
                    invoiceId,
                    UUID.randomUUID(),
                    "X",
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    false,
                    100,
                    0,
                    100,
                    0,
                    100,
                    false,
                    NOW)));
    Map<String, Object> detail = service.getDetail(owner, invoiceId);
    assertThat(detail).doesNotContainKey("prescribing_doctor");
    assertThat(detail).doesNotContainKey("mrp_savings");
    @SuppressWarnings("unchecked")
    Map<String, Object> line = ((List<Map<String, Object>>) detail.get("line_items")).getFirst();
    assertThat(line).doesNotContainKey("hsn_code");
  }

  @Test
  void shareValidationAndChannelUnavailable() {
    when(invoiceStore.findById(pharmacy, invoiceId)).thenReturn(Optional.of(invoice()));
    when(invoiceStore.listItems(invoiceId)).thenReturn(List.of());
    assertThatThrownBy(() -> service.share(owner, invoiceId, null, "+91"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.share(owner, invoiceId, "FAX", "+919876543210"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.share(owner, null, "SMS", "+919876543210"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVOICE_NOT_FOUND");
    assertThatThrownBy(() -> service.share(owner, invoiceId, "SMS", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_RECIPIENT");
    assertThatThrownBy(() -> service.share(owner, invoiceId, "SMS", "abc"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_RECIPIENT");
    assertThatThrownBy(() -> service.share(owner, invoiceId, "EMAIL", "not-an-email"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_RECIPIENT");

    when(notificationPort.shareInvoice(any(), any(), any(), any(), any(), any()))
        .thenThrow(new AppException("CHANNEL_UNAVAILABLE", "down", 503));
    assertThatThrownBy(() -> service.share(owner, invoiceId, "EMAIL", "a@b.com"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CHANNEL_UNAVAILABLE");

    // doReturn avoids re-invoking the previous thenThrow stub during setup
    doReturn(new PosNotificationPort.ShareResult("sms_1", NOW))
        .when(notificationPort)
        .shareInvoice(any(), any(), any(), eq(ShareChannel.SMS), any(), any());
    Map<String, Object> ok = service.share(owner, invoiceId, "SMS", "9876543210");
    assertThat(ok.get("recipient")).isEqualTo("9876543210");
  }

  @Test
  void patchSettingsValidationAndOwnerOnly() {
    assertThatThrownBy(() -> service.patchSettings(staff, Map.of("invoice_prefix", "AB")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(() -> service.patchSettings(owner, Map.of("accent_color", "blue")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_ACCENT_COLOR");
    assertThatThrownBy(
            () ->
                service.patchSettings(owner, Map.of("bank_details", Map.of("ifsc_code", "SHORT"))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_IFSC_CODE");
    assertThatThrownBy(() -> service.patchSettings(owner, Map.of("bank_details", "x")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.patchSettings(owner, Map.of("template", "WEIRD")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.patchSettings(owner, Map.of("document_title", "x".repeat(51))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.patchSettings(owner, Map.of("signatory_label", "x".repeat(101))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.patchSettings(owner, Map.of("terms_and_conditions", "x".repeat(1001))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.patchSettings(owner, Map.of("footer_note", "x".repeat(501))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    Map<String, Object> body = new HashMap<>();
    body.put("template", "THERMAL");
    body.put("accent_color", "#ABCDEF");
    body.put("logo_url", "");
    body.put("signature_url", "https://sig");
    body.put("document_title", "Bill");
    body.put("invoice_prefix", "ab12");
    body.put("signatory_label", "Owner");
    body.put(
        "bank_details",
        Map.of(
            "bank_name", "HDFC",
            "account_number", "123",
            "ifsc_code", "hdfc0001234",
            "upi_id", "a@upi"));
    body.put("terms_and_conditions", "t");
    body.put("footer_note", "f");
    body.put("show_mrp_savings", true);
    body.put("show_doctor", "true");
    body.put("show_hsn", false);
    body.put("print_bank_details", null);
    Map<String, Object> patched = service.patchSettings(owner, body);
    assertThat(patched.get("invoice_prefix")).isEqualTo("AB12");
    assertThat(patched.get("template")).isEqualTo("THERMAL");

    Map<String, Object> nullBody = service.patchSettings(owner, null);
    assertThat(nullBody.get("invoice_prefix")).isEqualTo("INV");

    Map<String, Object> bankMap = new HashMap<>();
    bankMap.put("bank_name", "SBI");
    bankMap.put("upi_id", null);
    Map<String, Object> bankOnly = service.patchSettings(owner, Map.of("bank_details", bankMap));
    assertThat(bankOnly.get("template")).isEqualTo("MINIMAL");

    Map<String, Object> logoNull = new HashMap<>();
    logoNull.put("logo_url", null);
    logoNull.put("template", null);
    logoNull.put("accent_color", null);
    logoNull.put("document_title", null);
    logoNull.put("invoice_prefix", null);
    logoNull.put("signatory_label", null);
    logoNull.put("bank_details", null);
    logoNull.put("terms_and_conditions", "");
    logoNull.put("footer_note", "");
    logoNull.put("show_mrp_savings", Boolean.FALSE);
    service.patchSettings(owner, logoNull);

    when(invoiceStore.findById(pharmacy, invoiceId)).thenReturn(Optional.of(invoice()));
    when(invoiceStore.listItems(invoiceId)).thenReturn(List.of());
    assertThatThrownBy(() -> service.share(owner, invoiceId, " ", "+919876543210"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.share(owner, invoiceId, "SMS", "  "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_RECIPIENT");

    Map<String, Object> settingsMap = service.getSettings(owner);
    assertThat(settingsMap.get("template")).isEqualTo("MINIMAL");

    assertThat(InvoiceService.newMessageId("WHATSAPP")).startsWith("whatsapp_msg_");
  }

  @Test
  void pdfDefaultTemplateAndRateLimit() {
    when(invoiceStore.findById(pharmacy, invoiceId)).thenReturn(Optional.of(invoice()));
    when(invoiceStore.listItems(invoiceId)).thenReturn(List.of());
    InvoiceService.FileExport pdf = service.pdf(owner, invoiceId, " ");
    assertThat(pdf.contentType()).isEqualTo("application/pdf");
    assertThat(pdf.bytes()[0]).isEqualTo((byte) '%');

    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(() -> service.getSettings(owner))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void adminReadRequiresPharmacyIdOnListAndUsesFindByIdAny() {
    MedmatePrincipal admin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(admin, null, null, null, null, null, 1, 20, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    when(invoiceStore.count(eq(pharmacy), any(), any(), any(), any(), any())).thenReturn(0L);
    when(invoiceStore.list(eq(pharmacy), any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(List.of());
    InvoiceService.ListResult page =
        (InvoiceService.ListResult)
            service.list(admin, null, null, null, null, null, 1, 20, null, pharmacy);
    assertThat(page.data().get("invoices")).asList().isEmpty();

    when(invoiceStore.findByIdAny(invoiceId)).thenReturn(Optional.of(invoice()));
    when(invoiceStore.listItems(invoiceId)).thenReturn(List.of());
    assertThat(service.getDetail(admin, invoiceId).get("invoice_id"))
        .isEqualTo(invoiceId.toString());
    assertThat(service.pdf(admin, invoiceId, null).bytes()[0]).isEqualTo((byte) '%');

    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "c");
    assertThatThrownBy(() -> service.getDetail(customer, invoiceId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.getDetail(null, invoiceId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.getDetail(admin, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVOICE_NOT_FOUND");
    when(invoiceStore.findByIdAny(invoiceId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getDetail(admin, invoiceId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVOICE_NOT_FOUND");

    MedmatePrincipal staffNoPharmacy =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () -> service.list(staffNoPharmacy, null, null, null, null, null, 1, 20, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    MedmatePrincipal support =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    when(invoiceStore.findByIdAny(invoiceId)).thenReturn(Optional.of(invoice()));
    assertThat(service.pdf(support, invoiceId, "MINIMAL").contentType())
        .isEqualTo("application/pdf");

    MedmatePrincipal compliance =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    when(invoiceStore.findByIdAny(invoiceId)).thenReturn(Optional.of(invoice()));
    assertThat(service.getDetail(compliance, invoiceId).get("invoice_id"))
        .isEqualTo(invoiceId.toString());
  }

  @Test
  void pdfWithSavingsFooterAndDoctor() {
    settings =
        new InvoiceSettings(
            pharmacy,
            InvoiceTemplate.MODERN,
            "#2563EB",
            null,
            null,
            "Tax Invoice",
            "INV",
            "Sign",
            null,
            null,
            "Bye",
            true,
            true,
            true,
            false,
            NOW);
    when(settingsStore.getOrCreate(pharmacy)).thenReturn(settings);
    when(pharmacyPort.findById(pharmacy))
        .thenReturn(Optional.of(new PosPharmacyPort.PharmacyInfo(null, "Addr", "1", null, "DL")));
    when(invoiceStore.findById(pharmacy, invoiceId)).thenReturn(Optional.of(invoice()));
    when(invoiceStore.listItems(invoiceId))
        .thenReturn(
            List.of(
                new InvoiceItem(
                    UUID.randomUUID(),
                    invoiceId,
                    UUID.randomUUID(),
                    "Med",
                    "3004",
                    UUID.randomUUID(),
                    "B1",
                    LocalDate.of(2027, 1, 1),
                    10,
                    1,
                    false,
                    100,
                    12,
                    89,
                    11,
                    100,
                    false,
                    NOW),
                new InvoiceItem(
                    UUID.randomUUID(),
                    invoiceId,
                    UUID.randomUUID(),
                    "NoHsn",
                    null,
                    null,
                    null,
                    null,
                    1,
                    1,
                    false,
                    50,
                    0,
                    50,
                    0,
                    50,
                    false,
                    NOW)));
    InvoiceService.FileExport pdf = service.pdf(owner, invoiceId, null);
    assertThat(pdf.bytes()[0]).isEqualTo((byte) '%');

    // show_hsn=false skips HSN append even when item has hsn_code
    settings =
        new InvoiceSettings(
            pharmacy,
            InvoiceTemplate.MODERN,
            "#2563EB",
            null,
            null,
            "Tax Invoice",
            "INV",
            "Sign",
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            NOW);
    when(settingsStore.getOrCreate(pharmacy)).thenReturn(settings);
    assertThat(service.pdf(owner, invoiceId, "MODERN").filename()).endsWith(".pdf");
  }

  private Invoice invoice() {
    return new Invoice(
        invoiceId,
        pharmacy,
        "INV-2026-07-000001",
        null,
        InvoiceChannel.COUNTER,
        null,
        "Priya",
        "+9198",
        "Dr X",
        100,
        0,
        10,
        100,
        PaymentMethod.CASH,
        PaymentStatus.PAID,
        null,
        100,
        0,
        20,
        InvoiceStatus.ACTIVE,
        null,
        NOW);
  }
}

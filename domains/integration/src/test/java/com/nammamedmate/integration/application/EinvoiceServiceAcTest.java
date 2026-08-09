package com.nammamedmate.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.integration.adapter.out.client.StubGspClient;
import com.nammamedmate.integration.domain.EinvoiceApiTypes;
import com.nammamedmate.integration.domain.EinvoiceIrnRecord;
import com.nammamedmate.integration.domain.EinvoiceStatuses;
import com.nammamedmate.integration.support.InMemoryStores;
import com.nammamedmate.kernel.error.AppException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EinvoiceServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:30:00Z");
  private static final String SELLER = "29ABCDE1234F1ZW";
  private static final String BUYER = "27AAPFU0939F1ZV";

  private InMemoryStores.EinvoiceRecords records;
  private InMemoryStores.EinvoiceLogs logs;
  private InMemoryStores.PharmacyFlags flags;
  private List<String> events;
  private MutableClock clock;
  private EinvoiceService service;

  @BeforeEach
  void setUp() {
    records = new InMemoryStores.EinvoiceRecords();
    logs = new InMemoryStores.EinvoiceLogs();
    flags = new InMemoryStores.PharmacyFlags();
    events = new ArrayList<>();
    clock = new MutableClock(NOW);
    service =
        new EinvoiceService(
            new StubGspClient(clock),
            records,
            logs,
            flags,
            (type, agg, id, payload) -> events.add(type),
            clock);
  }

  @Test
  void ac001_invalidSchemaReturnsField() {
    Map<String, Object> bad = validInvoice();
    bad.remove("invoice_number");
    assertThatThrownBy(() -> service.generateIrn(null, null, bad))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException app = (AppException) ex;
              assertThat(app.code()).isEqualTo("INVALID_INVOICE_SCHEMA");
              assertThat(app.httpStatus()).isEqualTo(422);
              assertThat(app.details()).containsEntry("field", "invoice_number");
              assertThat(app.getMessage()).contains("invoice_number");
            });
  }

  @Test
  void ac002_duplicateReturnsExistingWithAlreadyExisted() {
    Map<String, Object> first = service.generateIrn(null, null, validInvoice());
    assertThat(first.get("already_existed")).isEqualTo(false);
    assertThat(first.get("irn")).isNotNull();
    Map<String, Object> second = service.generateIrn(null, null, validInvoice());
    assertThat(second.get("already_existed")).isEqualTo(true);
    assertThat(second.get("irn")).isEqualTo(first.get("irn"));
  }

  @Test
  void ac003_cancelAfter24hBlocked() {
    Map<String, Object> gen = service.generateIrn(null, null, validInvoice());
    String irn = (String) gen.get("irn");
    clock.set(NOW.plus(Duration.ofHours(24).plusSeconds(1)));
    assertThatThrownBy(() -> service.cancelIrn(irn, "1", "too late"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IRN_CANCELLATION_WINDOW_EXPIRED");
  }

  @Test
  void ac004_statusShowsCancelledAt() {
    Map<String, Object> gen = service.generateIrn(null, null, validInvoice());
    String irn = (String) gen.get("irn");
    Map<String, Object> cancelled = service.cancelIrn(irn, "2", "data error");
    assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
    assertThat(cancelled.get("cancelled_at")).isNotNull();
    Map<String, Object> status = service.status(irn);
    assertThat(status.get("status")).isEqualTo("CANCELLED");
    assertThat(status.get("cancelled_at")).isNotNull();
  }

  @Test
  void ac005_disabledPharmacySkipsWithoutError() {
    UUID pharmacyId = UUID.randomUUID();
    flags.put(pharmacyId, false);
    Map<String, Object> result = service.generateIrn(pharmacyId, UUID.randomUUID(), validInvoice());
    assertThat(result.get("irn")).isNull();
    assertThat(result.get("skipped")).isEqualTo(true);
    assertThat(records.size()).isZero();
    assertThat(logs.all().get(0).responseStatus()).isEqualTo("SKIPPED");
  }

  @Test
  void ac006_storesSignedInvoiceJsonWithSignature() {
    Map<String, Object> gen = service.generateIrn(null, null, validInvoice());
    String signed = (String) gen.get("signed_invoice_json");
    assertThat(signed).contains("\"Signature\"");
    assertThat(signed).contains("\"Version\":\"1.1\"");
    Optional<EinvoiceIrnRecord> stored = records.findByIrn((String) gen.get("irn"));
    assertThat(stored).isPresent();
    assertThat(stored.get().signedInvoiceJson()).contains("Signature");
  }

  @Test
  void ac007_cancelledCannotRegenerateSameInvoiceNumber() {
    Map<String, Object> gen = service.generateIrn(null, null, validInvoice());
    service.cancelIrn((String) gen.get("irn"), "1", "dup");
    assertThatThrownBy(() -> service.generateIrn(null, null, validInvoice()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DUPLICATE_IRN");
  }

  @Test
  void ac008_nicCallsAreAuditLogged() {
    service.generateIrn(null, null, validInvoice());
    assertThat(logs.all()).isNotEmpty();
    assertThat(logs.all().get(0).apiType()).isEqualTo(EinvoiceApiTypes.GENERATE_IRN);
    assertThat(logs.all().get(0).latencyMs()).isGreaterThanOrEqualTo(0);
  }

  @Test
  void sellerNotRegisteredAndNicDown() {
    Map<String, Object> notReg = validInvoice();
    notReg.put("seller_gstin", "29AAAAA0000A1ZY");
    assertThatThrownBy(() -> service.generateIrn(null, null, notReg))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SELLER_GSTIN_NOT_REGISTERED");

    Map<String, Object> down = validInvoice();
    down.put("seller_gstin", "29AAAAA9999A1ZG");
    down.put("invoice_number", "INV-DOWN-1");
    assertThatThrownBy(() -> service.generateIrn(null, null, down))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NIC_PORTAL_UNAVAILABLE");
  }

  @Test
  void cancelAlreadyCancelledAndNotFoundAndBadReason() {
    Map<String, Object> gen = service.generateIrn(null, null, validInvoice());
    String irn = (String) gen.get("irn");
    service.cancelIrn(irn, "3", "order cancelled");
    assertThatThrownBy(() -> service.cancelIrn(irn, "3", "again"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IRN_ALREADY_CANCELLED");
    assertThatThrownBy(() -> service.cancelIrn("deadbeef" + "0".repeat(56), "1", "x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IRN_NOT_FOUND");
    assertThatThrownBy(() -> service.cancelIrn(irn, "9", "bad"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_INVOICE_SCHEMA");
    assertThatThrownBy(() -> service.cancelIrn("", "1", "x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IRN_NOT_FOUND");
  }

  @Test
  void statusNotFoundAndEnabledPharmacyGenerates() {
    assertThatThrownBy(() -> service.status("deadbeef" + "1".repeat(56)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IRN_NOT_FOUND");
    UUID pharmacyId = UUID.randomUUID();
    flags.put(pharmacyId, true);
    Map<String, Object> gen =
        service.generateIrn(pharmacyId, UUID.randomUUID(), validInvoice("INV-EN-1"));
    assertThat(gen.get("irn")).isNotNull();
    assertThat(service.status((String) gen.get("irn")).get("status"))
        .isEqualTo(EinvoiceStatuses.ACTIVE);
  }

  @Test
  void tokenRefreshSuccessAndCriticalFailure() {
    // Stub token created at NOW+24h; advance into the 1h proactive refresh window.
    clock.set(NOW.plus(Duration.ofHours(23).plusMinutes(30)));
    service.refreshTokenIfNeeded();
    assertThat(
            logs.all().stream().anyMatch(l -> EinvoiceApiTypes.TOKEN_REFRESH.equals(l.apiType())))
        .isTrue();

    AtomicReference<String> lastEvent = new AtomicReference<>();
    clock.set(NOW.plus(Duration.ofHours(30)));
    // Construct after clock advance so stub token is already inside the refresh window.
    EinvoiceService failing =
        new EinvoiceService(
            new StubGspClient(clock, true),
            records,
            logs,
            flags,
            (type, agg, id, payload) -> lastEvent.set(type),
            clock);
    clock.set(NOW.plus(Duration.ofHours(53).plusMinutes(30)));
    assertThatThrownBy(failing::refreshTokenIfNeeded)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NIC_PORTAL_UNAVAILABLE");
    assertThat(lastEvent.get()).isEqualTo("integration.gsp_token_refresh_failed");
  }

  @Test
  void tokenRefreshSkippedWhenFresh() {
    StubGspClient gsp = new StubGspClient(clock);
    EinvoiceService svc = new EinvoiceService(gsp, records, logs, flags, (t, a, i, p) -> {}, clock);
    int logSize = logs.size();
    svc.refreshTokenIfNeeded();
    // stub starts with 24h token → no refresh
    assertThat(logs.size()).isEqualTo(logSize);
  }

  public static Map<String, Object> validInvoice() {
    return validInvoice("INV-2026-07-001");
  }

  public static Map<String, Object> validInvoice(String number) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("sl_no", 1);
    item.put("product_name", "Metformin 500mg Tablet");
    item.put("hsn_code", "30049099");
    item.put("qty", 100);
    item.put("unit", "NOS");
    item.put("unit_price", 8.40);
    item.put("discount", 0);
    item.put("assbl_value", 840.00);
    item.put("gst_rate", 12);
    item.put("igst_amount", 0);
    item.put("cgst_amount", 50.40);
    item.put("sgst_amount", 50.40);
    item.put("total", 940.80);
    Map<String, Object> tax = new LinkedHashMap<>();
    tax.put("taxable_value", 840.00);
    tax.put("igst", 0);
    tax.put("cgst", 50.40);
    tax.put("sgst", 50.40);
    tax.put("total_invoice_value", 940.80);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("seller_gstin", SELLER);
    data.put("buyer_gstin", BUYER);
    data.put("invoice_number", number);
    data.put("invoice_date", "2026-07-24");
    data.put("supply_type", "B2B");
    data.put("invoice_type", "INV");
    data.put("items", List.of(item));
    data.put("tax_amounts", tax);
    return data;
  }

  /** Mutable UTC clock for AC-003 window tests. */
  public static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void set(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}

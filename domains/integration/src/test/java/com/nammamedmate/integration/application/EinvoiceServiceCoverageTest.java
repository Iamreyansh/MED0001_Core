package com.nammamedmate.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.integration.adapter.out.client.StubGspClient;
import com.nammamedmate.integration.application.port.out.GspClientPort;
import com.nammamedmate.integration.domain.EinvoiceIrnRecord;
import com.nammamedmate.integration.domain.EinvoiceStatuses;
import com.nammamedmate.integration.domain.FinancialYears;
import com.nammamedmate.integration.support.InMemoryStores;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EinvoiceServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void statusFallsBackToGspWhenNotLocal() {
    StubGspClient gsp = new StubGspClient(CLOCK);
    InMemoryStores.EinvoiceRecords records = new InMemoryStores.EinvoiceRecords();
    InMemoryStores.EinvoiceLogs logs = new InMemoryStores.EinvoiceLogs();
    EinvoiceService service =
        new EinvoiceService(
            gsp, records, logs, new InMemoryStores.PharmacyFlags(), (t, a, i, p) -> {}, CLOCK);
    GspClientPort.IrnResult remote = gsp.generateIrn(EinvoiceServiceAcTest.validInvoice());
    Map<String, Object> status = service.status(remote.irn());
    assertThat(status.get("status")).isEqualTo("ACTIVE");
    assertThat(status.get("ack_number")).isEqualTo(remote.ackNumber());
  }

  @Test
  void generateWithNullAckDateAndRemoteStatusNulls() {
    GspClientPort gsp =
        new GspClientPort() {
          @Override
          public IrnResult generateIrn(Map<String, Object> invoiceData) {
            return new IrnResult("d".repeat(64), "9", null, "qr", "{\"Signature\":\"x\"}");
          }

          @Override
          public void cancelIrn(String irn, String cancelReasonCode, String cancelRemark) {}

          @Override
          public IrnStatusResult getStatus(String irn) {
            return new IrnStatusResult(irn, "ACTIVE", "9", null, null);
          }

          @Override
          public TokenState refreshToken() {
            return new TokenState("t", NOW.plusSeconds(3600));
          }

          @Override
          public Optional<TokenState> currentToken() {
            return Optional.of(new TokenState("t", NOW.plus(Duration.ofHours(24))));
          }
        };
    EinvoiceService service =
        new EinvoiceService(
            gsp,
            new InMemoryStores.EinvoiceRecords(),
            new InMemoryStores.EinvoiceLogs(),
            new InMemoryStores.PharmacyFlags(),
            (t, a, i, p) -> {},
            CLOCK);
    Map<String, Object> gen =
        service.generateIrn(null, null, EinvoiceServiceAcTest.validInvoice("INV-NULL-ACK"));
    assertThat(gen.get("ack_date")).isNotNull();
    // wipe local so status hits remote null dates
    EinvoiceService remoteOnly =
        new EinvoiceService(
            gsp,
            new InMemoryStores.EinvoiceRecords(),
            new InMemoryStores.EinvoiceLogs(),
            new InMemoryStores.PharmacyFlags(),
            (t, a, i, p) -> {},
            CLOCK);
    Map<String, Object> status = remoteOnly.status("d".repeat(64));
    assertThat(status.get("ack_date")).isNull();
    assertThat(status.get("cancelled_at")).isNull();

    GspClientPort cancelledRemote =
        new GspClientPort() {
          @Override
          public IrnResult generateIrn(Map<String, Object> invoiceData) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void cancelIrn(String irn, String cancelReasonCode, String cancelRemark) {}

          @Override
          public IrnStatusResult getStatus(String irn) {
            return new IrnStatusResult(irn, "CANCELLED", "1", NOW, NOW);
          }

          @Override
          public TokenState refreshToken() {
            return new TokenState("t", NOW.plusSeconds(3600));
          }

          @Override
          public Optional<TokenState> currentToken() {
            return Optional.empty();
          }
        };
    Map<String, Object> cancelledStatus =
        new EinvoiceService(
                cancelledRemote,
                new InMemoryStores.EinvoiceRecords(),
                new InMemoryStores.EinvoiceLogs(),
                new InMemoryStores.PharmacyFlags(),
                (t, a, i, p) -> {},
                CLOCK)
            .status("e".repeat(64));
    assertThat(cancelledStatus.get("cancelled_at")).isNotNull();
  }

  @Test
  void statusErrorBranchWhenNotIrnNotFoundCode() {
    GspClientPort down =
        new GspClientPort() {
          @Override
          public IrnResult generateIrn(Map<String, Object> invoiceData) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void cancelIrn(String irn, String cancelReasonCode, String cancelRemark) {}

          @Override
          public IrnStatusResult getStatus(String irn) {
            throw new AppException("NIC_PORTAL_UNAVAILABLE", "down", 503);
          }

          @Override
          public TokenState refreshToken() {
            return new TokenState("t", NOW.plusSeconds(3600));
          }

          @Override
          public Optional<TokenState> currentToken() {
            return Optional.empty();
          }
        };
    EinvoiceService service =
        new EinvoiceService(
            down,
            new InMemoryStores.EinvoiceRecords(),
            new InMemoryStores.EinvoiceLogs(),
            new InMemoryStores.PharmacyFlags(),
            (t, a, i, p) -> {},
            CLOCK);
    assertThatThrownBy(() -> service.status("zzzzzzzz"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NIC_PORTAL_UNAVAILABLE");

    GspClientPort weird404 =
        new GspClientPort() {
          @Override
          public IrnResult generateIrn(Map<String, Object> invoiceData) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void cancelIrn(String irn, String cancelReasonCode, String cancelRemark) {}

          @Override
          public IrnStatusResult getStatus(String irn) {
            throw new AppException("GONE", "missing", 404);
          }

          @Override
          public TokenState refreshToken() {
            return new TokenState("t", NOW.plusSeconds(3600));
          }

          @Override
          public Optional<TokenState> currentToken() {
            return Optional.empty();
          }
        };
    assertThatThrownBy(
            () ->
                new EinvoiceService(
                        weird404,
                        new InMemoryStores.EinvoiceRecords(),
                        new InMemoryStores.EinvoiceLogs(),
                        new InMemoryStores.PharmacyFlags(),
                        (t, a, i, p) -> {},
                        CLOCK)
                    .status("yy"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GONE");
  }

  @Test
  void cancelWithNullRemarkUsesEmptyString() {
    EinvoiceService service =
        new EinvoiceService(
            new StubGspClient(CLOCK),
            new InMemoryStores.EinvoiceRecords(),
            new InMemoryStores.EinvoiceLogs(),
            new InMemoryStores.PharmacyFlags(),
            (t, a, i, p) -> {},
            CLOCK);
    Map<String, Object> gen =
        service.generateIrn(null, null, EinvoiceServiceAcTest.validInvoice("INV-NULL-RMK"));
    Map<String, Object> cancelled = service.cancelIrn((String) gen.get("irn"), "1", null);
    assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
  }

  @Test
  void cancelPropagatesGspFailure() {
    GspClientPort gsp =
        new GspClientPort() {
          @Override
          public IrnResult generateIrn(Map<String, Object> invoiceData) {
            return new IrnResult("b".repeat(64), "1", NOW, "qr", "{\"Signature\":\"x\"}");
          }

          @Override
          public void cancelIrn(String irn, String cancelReasonCode, String cancelRemark) {
            throw new AppException("NIC_PORTAL_UNAVAILABLE", "down", 503);
          }

          @Override
          public IrnStatusResult getStatus(String irn) {
            throw new AppException("IRN_NOT_FOUND", "missing", 404);
          }

          @Override
          public TokenState refreshToken() {
            return new TokenState("t", NOW.plus(Duration.ofHours(24)));
          }

          @Override
          public Optional<TokenState> currentToken() {
            return Optional.empty();
          }
        };
    InMemoryStores.EinvoiceRecords records = new InMemoryStores.EinvoiceRecords();
    String irn = "b".repeat(64);
    records.insert(
        new EinvoiceIrnRecord(
            Ids.newId(),
            null,
            null,
            irn,
            "1",
            NOW,
            "29ABCDE1234F1ZW",
            "27AAPFU0939F1ZV",
            "INV-X",
            LocalDate.of(2026, 7, 24),
            "INV",
            FinancialYears.of(LocalDate.of(2026, 7, 24)),
            new BigDecimal("10.00"),
            "qr",
            "{}",
            EinvoiceStatuses.ACTIVE,
            null,
            null,
            NOW,
            null));
    EinvoiceService service =
        new EinvoiceService(
            gsp,
            records,
            new InMemoryStores.EinvoiceLogs(),
            new InMemoryStores.PharmacyFlags(),
            (t, a, i, p) -> {},
            CLOCK);
    assertThatThrownBy(() -> service.cancelIrn(irn, "1", "x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NIC_PORTAL_UNAVAILABLE");
    service.refreshTokenIfNeeded();
    assertThatThrownBy(() -> service.status("missing"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IRN_NOT_FOUND");
  }

  @Test
  void cancelWithinWindowSucceedsAtExactBoundary() {
    EinvoiceServiceAcTest.MutableClock clock = new EinvoiceServiceAcTest.MutableClock(NOW);
    EinvoiceService service =
        new EinvoiceService(
            new StubGspClient(clock),
            new InMemoryStores.EinvoiceRecords(),
            new InMemoryStores.EinvoiceLogs(),
            new InMemoryStores.PharmacyFlags(),
            (t, a, i, p) -> {},
            clock);
    Map<String, Object> gen = service.generateIrn(null, null, EinvoiceServiceAcTest.validInvoice());
    clock.set(NOW.plus(Duration.ofHours(24)));
    Map<String, Object> cancelled = service.cancelIrn((String) gen.get("irn"), "4", "others");
    assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
  }

  @Test
  void unknownPharmacyIdStillGenerates() {
    EinvoiceService service =
        new EinvoiceService(
            new StubGspClient(CLOCK),
            new InMemoryStores.EinvoiceRecords(),
            new InMemoryStores.EinvoiceLogs(),
            new InMemoryStores.PharmacyFlags(),
            (t, a, i, p) -> {},
            CLOCK);
    Map<String, Object> gen =
        service.generateIrn(
            UUID.randomUUID(), null, EinvoiceServiceAcTest.validInvoice("INV-UNK-1"));
    assertThat(gen.get("irn")).isNotNull();
  }

  @Test
  void financialYearsNullRejected() {
    assertThatThrownBy(() -> FinancialYears.of(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refreshWrapsNonAppExceptionAndLongSummaryTruncates() {
    GspClientPort gsp =
        new GspClientPort() {
          @Override
          public IrnResult generateIrn(Map<String, Object> invoiceData) {
            return new IrnResult("c".repeat(64), "1", NOW, "qr", "{\"Signature\":\"x\"}");
          }

          @Override
          public void cancelIrn(String irn, String cancelReasonCode, String cancelRemark) {}

          @Override
          public IrnStatusResult getStatus(String irn) {
            throw new AppException("NIC_PORTAL_UNAVAILABLE", "down", 503);
          }

          @Override
          public TokenState refreshToken() {
            throw new IllegalStateException("boom");
          }

          @Override
          public Optional<TokenState> currentToken() {
            return Optional.empty();
          }
        };
    InMemoryStores.EinvoiceLogs logs = new InMemoryStores.EinvoiceLogs();
    EinvoiceService service =
        new EinvoiceService(
            gsp,
            new InMemoryStores.EinvoiceRecords(),
            logs,
            new InMemoryStores.PharmacyFlags(),
            (t, a, i, p) -> {},
            CLOCK);
    assertThatThrownBy(service::refreshTokenIfNeeded)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NIC_PORTAL_UNAVAILABLE");

    Map<String, Object> longInv = EinvoiceServiceAcTest.validInvoice("N".repeat(180));
    Map<String, Object> gen = service.generateIrn(null, null, longInv);
    assertThat(gen.get("irn")).isNotNull();
    assertThat(logs.all().get(logs.size() - 1).requestSummary().length()).isLessThanOrEqualTo(200);

    assertThatThrownBy(() -> service.status("ab"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NIC_PORTAL_UNAVAILABLE");
  }
}

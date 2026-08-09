package com.nammamedmate.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.FinancialLedgerQueryPort;
import com.nammamedmate.payment.application.port.out.FinancialLedgerQueryPort.DayKpis;
import com.nammamedmate.payment.application.port.out.FinancialLedgerQueryPort.LedgerPage;
import com.nammamedmate.payment.application.port.out.FinancialLedgerQueryPort.LedgerRow;
import com.nammamedmate.payment.application.port.out.TaxFilingObjectStore;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerFacadeServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T16:30:00Z");

  @Mock private FinancialLedgerQueryPort store;
  @Mock private TaxFilingObjectStore objects;

  private LedgerFacadeService service;
  private MedmatePrincipal finance;
  private MedmatePrincipal support;

  @BeforeEach
  void setUp() {
    service = new LedgerFacadeService(store, objects, Clock.fixed(NOW, ZoneOffset.UTC));
    finance =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    support =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
  }

  @Test
  void ac003_filterPayoutPharmacyAndRunningBalance() {
    UUID settlementId = UUID.randomUUID();
    LedgerRow row =
        new LedgerRow(
            UUID.randomUUID(),
            "PAYOUT_PHARMACY",
            settlementId,
            "SETTLEMENT",
            0L,
            4_732_000L,
            20_146_069L,
            "Settlement released",
            Instant.parse("2026-07-24T13:15:00Z"));
    when(store.list(any(), any(), any(), anyInt(), anyInt(), anyBoolean()))
        .thenReturn(new LedgerPage(List.of(row), 1));
    when(store.dayKpis(any(), any())).thenReturn(new DayKpis(18_500_000L, 1_480_000L, 273_600L));

    var result =
        service.browse(
            finance, "PAYOUT_PHARMACY", "2026-07-01", "2026-07-24", 1, 50, "created_at", "desc");

    assertThat(result.meta().total()).isEqualTo(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries = (List<Map<String, Object>>) result.data().get("entries");
    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().get("type")).isEqualTo("PAYOUT_PHARMACY");
    assertThat(entries.getFirst().get("running_balance")).isEqualTo(new BigDecimal("201460.69"));

    ArgumentCaptor<String[]> types = ArgumentCaptor.forClass(String[].class);
    verify(store).list(types.capture(), any(), any(), eq(1), eq(50), eq(false));
    assertThat(types.getValue()).containsExactly("PAYOUT_PHARMACY");
  }

  @Test
  void ac004_ac008_exportReturnsDownloadUrlAndCsvColumns() {
    UUID id = UUID.randomUUID();
    UUID ref = UUID.randomUUID();
    LedgerRow row =
        new LedgerRow(
            id,
            "ORDER_GMV",
            ref,
            "PAYMENT",
            49_500L,
            0L,
            49_500L,
            "Payment captured",
            Instant.parse("2026-07-15T10:00:00Z"));
    when(store.listAllForExport(any(), any(), any())).thenReturn(List.of(row));
    when(objects.createDownloadUrl(any(), any())).thenReturn("https://s3.example/ledger.csv");

    Map<String, Object> data = service.export(finance, "2026-07-01", "2026-07-31", null);

    assertThat(data.get("download_url")).isEqualTo("https://s3.example/ledger.csv");
    assertThat(data.get("record_count")).isEqualTo(1);
    assertThat(data.get("from_date")).isEqualTo("2026-07-01");
    assertThat(data.get("to_date")).isEqualTo("2026-07-31");
    assertThat(data.get("expires_at")).isNotNull();

    ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
    verify(objects).put(any(), bytes.capture(), eq("text/csv"));
    String csv = new String(bytes.getValue());
    assertThat(csv)
        .startsWith(
            "ledger_id,type,reference_id,reference_type,credit,debit,running_balance,description,created_at\n");
    assertThat(csv).contains(id.toString());
    assertThat(csv).contains("ORDER_GMV");
    assertThat(csv).contains("495.00");
  }

  @Test
  void ac005_exportRangeOver90DaysRejected() {
    assertThatThrownBy(() -> service.export(finance, "2026-01-01", "2026-07-31", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DATE_RANGE_TOO_LARGE");
  }

  @Test
  void ac006_kpiChipsFromLedgerToday() {
    when(store.list(any(), isNull(), isNull(), anyInt(), anyInt(), anyBoolean()))
        .thenReturn(new LedgerPage(List.of(), 0));
    when(store.dayKpis(any(), any())).thenReturn(new DayKpis(18_500_000L, 1_480_000L, 273_600L));

    var result = service.browse(finance, null, null, null, 1, 50, null, null);

    @SuppressWarnings("unchecked")
    Map<String, Object> chips = (Map<String, Object>) result.data().get("kpi_chips");
    assertThat(chips.get("gmv_today")).isEqualTo(new BigDecimal("185000.00"));
    assertThat(chips.get("commission_today")).isEqualTo(new BigDecimal("14800.00"));
    assertThat(chips.get("net_revenue_today")).isEqualTo(new BigDecimal("12064.00"));
  }

  @Test
  void ac007_nonFinanceForbidden() {
    assertThatThrownBy(() -> service.browse(support, null, null, null, 1, 50, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.export(support, "2026-07-01", "2026-07-31", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void exportInvalidRangeAndBrowseCsv() {
    assertThatThrownBy(() -> service.export(finance, "2026-07-31", "2026-07-01", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DATE_RANGE");
    assertThatThrownBy(() -> service.export(finance, null, "2026-07-01", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DATE_RANGE");

    when(store.listAllForExport(any(), any(), any())).thenReturn(List.of());
    byte[] csv = service.browseCsv(finance, "TCS", "2026-07-01", "2026-07-10");
    assertThat(new String(csv)).contains("ledger_id,type");
    ArgumentCaptor<String[]> types = ArgumentCaptor.forClass(String[].class);
    verify(store).listAllForExport(types.capture(), any(), any());
    assertThat(types.getValue()).containsExactlyInAnyOrder("TCS", "TCS_COLLECTED");
  }

  @Test
  void browseRejectsUnknownTypeAndBadSort() {
    assertThatThrownBy(() -> service.browse(finance, "NOPE", null, null, 1, 50, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_TYPE");
    assertThatThrownBy(() -> service.browse(finance, null, null, null, 1, 50, "amount", "desc"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SORT");
    assertThatThrownBy(() -> service.browse(finance, null, "2026-07-01", null, 1, 50, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DATE_RANGE");
    assertThatThrownBy(() -> service.browse(null, null, null, null, 1, 50, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void csvEscapesCommasAndTcsAlias() {
    LedgerRow row =
        new LedgerRow(
            UUID.randomUUID(),
            "TCS_COLLECTED",
            UUID.randomUUID(),
            "SETTLEMENT",
            1000L,
            0L,
            1000L,
            "TCS, collected",
            Instant.parse("2026-07-15T10:00:00Z"));
    String csv = LedgerFacadeService.buildCsv(List.of(row));
    assertThat(csv).contains("TCS");
    assertThat(csv).contains("\"TCS, collected\"");
    verify(objects, org.mockito.Mockito.never()).put(any(), any(), any());
  }

  @Test
  void exportUsesOneHourTtl() {
    when(store.listAllForExport(any(), any(), any())).thenReturn(List.of());
    when(objects.createDownloadUrl(any(), any())).thenReturn("url");
    service.export(finance, "2026-07-01", "2026-07-02", "ORDER_GMV");
    verify(objects).createDownloadUrl(any(), eq(Duration.ofHours(1)));
  }

  @Test
  void coverage_paginationDefaultsNullFieldsSuperAdminAndCsvEscapes() {
    MedmatePrincipal superAdmin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    when(store.list(any(), any(), any(), anyInt(), anyInt(), anyBoolean()))
        .thenReturn(new LedgerPage(List.of(), 0));
    when(store.dayKpis(any(), any())).thenReturn(new DayKpis(0, 0, 0));

    service.browse(superAdmin, null, null, null, null, null, "  ", "asc");
    verify(store).list(any(), isNull(), isNull(), eq(1), eq(50), eq(true));

    service.browse(finance, "  ", null, null, 0, 0, "created_at", "asc");
    verify(store, org.mockito.Mockito.atLeastOnce())
        .list(any(), isNull(), isNull(), eq(1), eq(1), eq(true));

    service.browse(finance, null, null, null, 2, 200, "created_at", "asc");
    verify(store).list(any(), isNull(), isNull(), eq(2), eq(100), eq(true));

    assertThatThrownBy(() -> service.browse(finance, null, null, "2026-07-01", 1, 10, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DATE_RANGE");
    assertThatThrownBy(() -> service.browse(finance, null, "2026-07-01", "  ", 1, 10, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DATE_RANGE");
    assertThatThrownBy(() -> service.export(finance, "  ", "2026-07-02", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DATE_RANGE");
    assertThatThrownBy(() -> service.export(finance, "2026-07-01", null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DATE_RANGE");

    LedgerRow sparse =
        new LedgerRow(
            UUID.randomUUID(), "REFUND", UUID.randomUUID(), "REFUND", 0L, 100L, 0L, null, null);
    when(store.list(any(), any(), any(), anyInt(), anyInt(), anyBoolean()))
        .thenReturn(new LedgerPage(List.of(sparse), 1));
    var browsed = service.browse(finance, "REFUND", "2026-07-10", "2026-07-10", 1, 1, null, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries = (List<Map<String, Object>>) browsed.data().get("entries");
    assertThat(entries.getFirst().get("description")).isEqualTo("");
    assertThat(entries.getFirst().get("created_at")).isNull();

    String csv =
        LedgerFacadeService.buildCsv(
            List.of(
                sparse,
                new LedgerRow(
                    UUID.randomUUID(),
                    "GATEWAY_FEE",
                    UUID.randomUUID(),
                    "PAYMENT",
                    0L,
                    50L,
                    50L,
                    "only,comma",
                    Instant.parse("2026-07-15T10:00:00Z")),
                new LedgerRow(
                    UUID.randomUUID(),
                    "WALLET_CREDIT",
                    UUID.randomUUID(),
                    "WALLET",
                    0L,
                    10L,
                    40L,
                    "only\"quote",
                    Instant.parse("2026-07-15T11:00:00Z")),
                new LedgerRow(
                    UUID.randomUUID(),
                    "COD_DEPOSIT",
                    UUID.randomUUID(),
                    "COD_DEPOSIT",
                    10L,
                    0L,
                    50L,
                    "only\nline",
                    Instant.parse("2026-07-15T12:00:00Z")),
                new LedgerRow(
                    UUID.randomUUID(),
                    "PAYOUT_RIDER",
                    UUID.randomUUID(),
                    "RIDER_PAYOUT",
                    0L,
                    10L,
                    40L,
                    "only\rreturn",
                    Instant.parse("2026-07-15T13:00:00Z"))));
    assertThat(csv).contains("\"only,comma\"");
    assertThat(csv).contains("\"only\"\"quote\"");
    assertThat(csv).contains("\"only\nline\"");
    assertThat(csv).contains("\"only\rreturn\"");
    assertThat(LedgerFacadeService.buildCsv(List.of()).lines().count()).isEqualTo(1);

    assertThatThrownBy(() -> service.export(finance, "2026-07-01", "not-a-date", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DATE_RANGE");
    assertThatThrownBy(
            () -> service.browse(finance, null, "2026-07-31", "2026-07-01", 1, 10, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DATE_RANGE");
    assertThatThrownBy(() -> service.export(finance, "2026-07-01", "", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_DATE_RANGE");

    assertThat(new LedgerFacadeService.PagedResult(null, null).data()).isEmpty();
    assertThat(new FinancialLedgerQueryPort.LedgerPage(null, 0).rows()).isEmpty();
  }
}

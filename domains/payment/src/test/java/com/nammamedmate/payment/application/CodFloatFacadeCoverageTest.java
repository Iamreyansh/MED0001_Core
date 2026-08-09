package com.nammamedmate.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.application.port.out.CodFloatAlertPort;
import com.nammamedmate.payment.application.port.out.CodFloatPort;
import com.nammamedmate.payment.application.port.out.CodFloatPort.DayAggregates;
import com.nammamedmate.payment.application.port.out.CodFloatPort.FloatRiderRow;
import com.nammamedmate.payment.application.port.out.CodFloatPort.FloatSnapshot;
import com.nammamedmate.payment.application.port.out.CodFloatPort.ReportRecord;
import com.nammamedmate.payment.application.port.out.CodFloatPort.RiderDayBreakdown;
import com.nammamedmate.payment.application.port.out.FinancialLedgerWriterPort;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CodFloatFacadeCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T13:00:00Z");

  @Mock private CodFloatPort floats;
  @Mock private CodFloatAlertPort alerts;
  @Mock private FinancialLedgerWriterPort ledger;

  private CodFloatFacadeService service;
  private final UUID adminId = UUID.randomUUID();
  private final MedmatePrincipal finance =
      new MedmatePrincipal(adminId, AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal superAdmin =
      new MedmatePrincipal(adminId, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service =
        new CodFloatFacadeService(
            floats, alerts, ledger, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    when(floats.floatLimitPaise()).thenReturn(200_000L);
  }

  @Test
  void unauthorizedAndDefaultPaginationAndSafeRider() {
    assertThatThrownBy(() -> service.floatSummary(null, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> CodFloatFacadeService.requireFinanceWrite(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");

    UUID riderId = UUID.randomUUID();
    Instant last = Instant.parse("2026-07-24T08:00:00Z");
    when(floats.floatBoard(any(), anyBoolean(), any(), any(), anyLong(), anyInt(), anyInt()))
        .thenReturn(
            new FloatSnapshot(
                List.of(new FloatRiderRow(riderId, "Ravi", "Zone", 10, 10, 100, last)),
                1,
                100,
                10,
                10,
                0,
                0));
    var page = service.floatSummary(superAdmin, null, null, 0, 0);
    assertThat(page.meta().page()).isEqualTo(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> riders = (List<Map<String, Object>>) page.data().get("riders");
    assertThat(riders.getFirst().get("risk_status")).isEqualTo("SAFE");
    assertThat(riders.getFirst().get("last_deposit_at")).isEqualTo(last.toString());
  }

  @Test
  void reportFutureDateAndBadBreakdownJsonAndCsvQuote() throws Exception {
    assertThatThrownBy(() -> service.reconciliationReport(finance, LocalDate.parse("2026-08-01")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DATE");

    when(floats.findReport(LocalDate.parse("2026-07-23")))
        .thenReturn(
            Optional.of(
                new ReportRecord(
                    UUID.randomUUID(),
                    LocalDate.parse("2026-07-23"),
                    1,
                    1,
                    1,
                    1,
                    0,
                    0,
                    "checked",
                    "BALANCED",
                    false,
                    NOW,
                    adminId,
                    "not-json")));
    Map<String, Object> report =
        service.reconciliationReport(finance, LocalDate.parse("2026-07-23"));
    assertThat(report.get("variance_reason")).isEqualTo("checked");
    assertThat(report.get("rider_breakdown")).isEqualTo(List.of());

    when(floats.findReport(LocalDate.parse("2026-07-22")))
        .thenReturn(
            Optional.of(
                new ReportRecord(
                    UUID.randomUUID(),
                    LocalDate.parse("2026-07-22"),
                    1,
                    1,
                    1,
                    1,
                    0,
                    0,
                    null,
                    "BALANCED",
                    false,
                    NOW,
                    null,
                    "[{\"rider_id\":\"x\",\"rider_name\":\"A, B\",\"orders\":1,\"collected\":1,\"deposited\":1,\"variance\":0,\"status\":\"MATCHED\"}]")));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    service.exportReconciliationCsv(finance, LocalDate.parse("2026-07-22"), out);
    assertThat(out.toString()).contains("\"A, B\"");
  }

  @Test
  void scheduledSkipsWhenAlreadyRunning_andAutoReconcileNullDate() {
    when(floats.tryClaimJob(any(), any(), any(), any())).thenReturn(false);
    service.runScheduledReconciliation(LocalDate.parse("2026-07-24"));
    verify(floats, never()).aggregatesForDay(any(), any());

    when(floats.tryClaimJob(any(), any(), any(), any())).thenReturn(true);
    when(floats.aggregatesForDay(any(), any()))
        .thenReturn(
            new DayAggregates(
                0, 0, 0, 0, List.of(new RiderDayBreakdown(UUID.randomUUID(), null, 0, 0, 0))));
    Map<String, Object> job = service.autoReconcile(finance, null);
    assertThat(job.get("date")).isEqualTo("2026-07-24");
  }

  @Test
  void exportIoFailure() {
    when(floats.findReport(any()))
        .thenReturn(
            Optional.of(
                new ReportRecord(
                    UUID.randomUUID(),
                    LocalDate.parse("2026-07-23"),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    null,
                    "BALANCED",
                    false,
                    NOW,
                    null,
                    "[]")));
    OutputStream boom =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("boom");
          }
        };
    assertThatThrownBy(
            () -> service.exportReconciliationCsv(finance, LocalDate.parse("2026-07-23"), boom))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("EXPORT_FAILED");
  }

  @Test
  void coverageBranches_txNullRiderCsvAndJsonFail() throws Exception {
    PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
    when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    ObjectMapper boom = mock(ObjectMapper.class);
    when(boom.writeValueAsString(any())).thenThrow(new JsonProcessingException("x") {});
    CodFloatFacadeService withTx =
        new CodFloatFacadeService(
            floats, alerts, ledger, boom, Clock.fixed(NOW, ZoneOffset.UTC), tm);

    when(floats.tryClaimJob(any(), any(), any(), any())).thenReturn(true);
    when(floats.aggregatesForDay(any(), any())).thenReturn(new DayAggregates(0, 0, 0, 0, null));
    assertThatThrownBy(() -> withTx.autoReconcile(finance, LocalDate.parse("2026-07-24")))
        .isInstanceOf(IllegalStateException.class);

    service.onDepositConfirmed(UUID.randomUUID(), null, 10_000L);
    verify(ledger).append(any(), any(), any(), anyLong(), anyLong(), any(), any());

    assertThat(new CodFloatFacadeService.PagedResult(null, PaginationMeta.of(1, 20, 0)).data())
        .isEmpty();
    assertThat(new CodFloatPort.FloatSnapshot(null, 0, 0, 0, 0, 0, 0).riders()).isEmpty();
    assertThat(new CodFloatPort.DayAggregates(0, 0, 0, 0, null).riders()).isEmpty();

    when(floats.findReport(any()))
        .thenReturn(
            Optional.of(
                new ReportRecord(
                    UUID.randomUUID(),
                    LocalDate.parse("2026-07-23"),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    null,
                    "BALANCED",
                    false,
                    NOW,
                    null,
                    null)));
    Map<String, Object> report =
        service.reconciliationReport(finance, LocalDate.parse("2026-07-23"));
    assertThat(report.get("rider_breakdown")).isEqualTo(List.of());

    when(floats.findReport(any()))
        .thenReturn(
            Optional.of(
                new ReportRecord(
                    UUID.randomUUID(),
                    LocalDate.parse("2026-07-23"),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    null,
                    "BALANCED",
                    false,
                    NOW,
                    null,
                    "[{\"rider_id\":null,\"rider_name\":\"x\\\"y\",\"orders\":1,\"collected\":1,\"deposited\":1,\"variance\":0,\"status\":\"MATCHED\"}]")));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    service.exportReconciliationCsv(finance, LocalDate.parse("2026-07-23"), out);
    assertThat(out.toString()).contains("x\"\"y");
  }

  @Test
  void limitClampAndBlankBreakdown() {
    when(floats.floatBoard(any(), anyBoolean(), any(), any(), anyLong(), anyInt(), anyInt()))
        .thenReturn(new FloatSnapshot(List.of(), 0, 0, 0, 0, 0, 0));
    var page = service.floatSummary(finance, null, false, 2, 500);
    assertThat(page.meta().limit()).isEqualTo(100);
    var page2 = service.floatSummary(finance, null, false, null, null);
    assertThat(page2.meta().page()).isEqualTo(1);
    assertThat(page2.meta().limit()).isEqualTo(20);
    var page3 = service.floatSummary(finance, null, false, 3, 10);
    assertThat(page3.meta().limit()).isEqualTo(10);

    when(floats.findReport(any()))
        .thenReturn(
            Optional.of(
                new ReportRecord(
                    UUID.randomUUID(),
                    LocalDate.parse("2026-07-23"),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    null,
                    "BALANCED",
                    false,
                    NOW,
                    null,
                    "  ")));
    assertThat(
            service
                .reconciliationReport(finance, LocalDate.parse("2026-07-23"))
                .get("rider_breakdown"))
        .isEqualTo(List.of());
  }

  @Test
  void financeWriteForbiddenAndCsvNewline() throws Exception {
    MedmatePrincipal ops =
        new MedmatePrincipal(adminId, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> CodFloatFacadeService.requireFinanceWrite(ops))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    CodFloatFacadeService.requireFinanceWrite(superAdmin);

    when(floats.findReport(any()))
        .thenReturn(
            Optional.of(
                new ReportRecord(
                    UUID.randomUUID(),
                    LocalDate.parse("2026-07-23"),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    null,
                    "BALANCED",
                    false,
                    NOW,
                    null,
                    "[{\"rider_id\":\"x\",\"rider_name\":\"line\\nbreak\",\"orders\":1,\"collected\":1,\"deposited\":1,\"variance\":0,\"status\":\"MATCHED\"}]")));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    service.exportReconciliationCsv(finance, LocalDate.parse("2026-07-23"), out);
    assertThat(out.toString()).contains("\"");
  }

  @Test
  void varianceBelowAlertThreshold_noAlert() {
    when(floats.tryClaimJob(any(), any(), any(), any())).thenReturn(true);
    when(floats.aggregatesForDay(any(), any()))
        .thenReturn(
            new DayAggregates(
                1,
                100_000L,
                100_000L,
                105_000L,
                List.of(new RiderDayBreakdown(UUID.randomUUID(), "R", 1, 100_000L, 105_000L))));
    service.autoReconcile(finance, LocalDate.parse("2026-07-24"));
    verify(alerts, never()).varianceAlert(any(), any(), anyLong(), any());
  }
}

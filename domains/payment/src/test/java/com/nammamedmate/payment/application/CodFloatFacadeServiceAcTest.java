package com.nammamedmate.payment.application;

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

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CodFloatFacadeServiceAcTest {

  /** 2026-07-24 18:30 IST = 13:00 UTC */
  private static final Instant NOW = Instant.parse("2026-07-24T13:00:00Z");

  @Mock private CodFloatPort floats;
  @Mock private CodFloatAlertPort alerts;
  @Mock private FinancialLedgerWriterPort ledger;

  private CodFloatFacadeService service;
  private final ObjectMapper om = new ObjectMapper();
  private final UUID adminId = UUID.randomUUID();
  private final UUID riderId = UUID.randomUUID();
  private final MedmatePrincipal finance =
      new MedmatePrincipal(adminId, AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal ops =
      new MedmatePrincipal(adminId, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  private final MedmatePrincipal support =
      new MedmatePrincipal(adminId, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service =
        new CodFloatFacadeService(floats, alerts, ledger, om, Clock.fixed(NOW, ZoneOffset.UTC));
    when(floats.floatLimitPaise()).thenReturn(200_000L);
  }

  @Test
  void ac001_floatSummary_totalInTransitIsSumOfCodInHand() {
    when(floats.floatBoard(any(), anyBoolean(), any(), any(), anyLong(), anyInt(), anyInt()))
        .thenReturn(
            new FloatSnapshot(
                List.of(
                    new FloatRiderRow(riderId, "Ravi", "Koramangala", 100_00, 0, 185_000L, null)),
                1,
                284_500L,
                182_000L,
                152_000L,
                86_000L,
                4));

    CodFloatFacadeService.PagedResult result = service.floatSummary(finance, null, false, 1, 20);
    @SuppressWarnings("unchecked")
    Map<String, Object> summary = (Map<String, Object>) result.data().get("summary");
    assertThat(summary.get("total_cod_in_transit")).isEqualTo(new BigDecimal("2845.00"));
    assertThat(result.meta().total()).isEqualTo(1);
  }

  @Test
  void ac002_floatRiskAndRiskOnlyFilter() {
    when(floats.floatBoard(isNull(), eq(true), any(), any(), eq(200_000L), eq(1), eq(20)))
        .thenReturn(
            new FloatSnapshot(
                List.of(
                    new FloatRiderRow(
                        riderId, "Suresh", "Indiranagar", 360_000L, 0, 360_000L, null)),
                1,
                360_000L,
                360_000L,
                0,
                360_000L,
                1));

    CodFloatFacadeService.PagedResult result = service.floatSummary(ops, null, true, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> riders = (List<Map<String, Object>>) result.data().get("riders");
    assertThat(riders.getFirst().get("risk_status")).isEqualTo("FLOAT_RISK");
    assertThat(riders.getFirst().get("in_hand")).isEqualTo(new BigDecimal("3600.00"));
  }

  @Test
  void ac003_scheduledReconciliation_persistsReportForYesterdayLookup() {
    LocalDate today = LocalDate.parse("2026-07-24");
    when(floats.tryClaimJob(any(), eq(today), isNull(), eq(NOW))).thenReturn(true);
    when(floats.aggregatesForDay(any(), any()))
        .thenReturn(
            new DayAggregates(
                2,
                420_000L,
                420_000L,
                420_000L,
                List.of(new RiderDayBreakdown(riderId, "Ravi", 2, 420_000L, 420_000L))));

    AtomicReference<ReportRecord> saved = new AtomicReference<>();
    org.mockito.Mockito.doAnswer(
            inv -> {
              saved.set(inv.getArgument(0));
              return null;
            })
        .when(floats)
        .completeReport(any());

    service.runScheduledReconciliation(today);

    assertThat(saved.get()).isNotNull();
    assertThat(saved.get().reconciliationStatus()).isEqualTo("BALANCED");
    assertThat(saved.get().variancePaise()).isZero();

    when(floats.findReport(today)).thenReturn(Optional.of(saved.get()));
    Map<String, Object> report = service.reconciliationReport(finance, today);
    assertThat(report.get("reconciliation_status")).isEqualTo("BALANCED");
    assertThat(report.get("variance_reason")).isNull();
  }

  @Test
  void ac004_varianceAbove100_triggersAlert() {
    LocalDate date = LocalDate.parse("2026-07-24");
    when(floats.tryClaimJob(any(), eq(date), eq(adminId), eq(NOW))).thenReturn(true);
    when(floats.aggregatesForDay(any(), any()))
        .thenReturn(
            new DayAggregates(
                1,
                100_000L,
                100_000L,
                115_000L,
                List.of(new RiderDayBreakdown(riderId, "Ravi", 1, 100_000L, 115_000L))));

    Map<String, Object> job = service.autoReconcile(finance, date);
    assertThat(job.get("status")).isEqualTo("RUNNING");
    verify(alerts).varianceAlert(any(), eq(date), eq(15_000L), eq("DISCREPANCY"));
    ArgumentCaptor<ReportRecord> cap = ArgumentCaptor.forClass(ReportRecord.class);
    verify(floats).completeReport(cap.capture());
    assertThat(cap.getValue().alertSent()).isTrue();
    assertThat(cap.getValue().varianceReason()).isNull();
  }

  @Test
  void ac005_autoReconcileFutureDate_invalidDate() {
    assertThatThrownBy(() -> service.autoReconcile(finance, LocalDate.parse("2026-07-25")))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_DATE");
  }

  @Test
  void ac006_autoReconcileWhileRunning_conflict() {
    when(floats.tryClaimJob(any(), any(), any(), any())).thenReturn(false);
    assertThatThrownBy(() -> service.autoReconcile(finance, LocalDate.parse("2026-07-24")))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("JOB_ALREADY_RUNNING");
  }

  @Test
  void ac007_exportCsvIncludesRiderBreakdown() throws Exception {
    UUID reportId = UUID.randomUUID();
    String breakdown =
        """
        [{"rider_id":"%s","rider_name":"Ravi Kumar","orders":14,"collected":4200.00,"deposited":4200.00,"variance":0.00,"status":"MATCHED"}]
        """
            .formatted(riderId);
    when(floats.findReport(LocalDate.parse("2026-07-23")))
        .thenReturn(
            Optional.of(
                new ReportRecord(
                    reportId,
                    LocalDate.parse("2026-07-23"),
                    14,
                    420_000L,
                    420_000L,
                    420_000L,
                    0,
                    0,
                    null,
                    "BALANCED",
                    false,
                    NOW,
                    adminId,
                    breakdown)));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    service.exportReconciliationCsv(finance, null, out);
    String csv = out.toString();
    assertThat(csv).contains("rider_id,rider_name,orders,collected,deposited,variance,status");
    assertThat(csv).contains("Ravi Kumar");
    assertThat(csv).contains("MATCHED");
  }

  @Test
  void reportNotGenerated_404() {
    when(floats.findReport(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.reconciliationReport(finance, LocalDate.parse("2026-07-20")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REPORT_NOT_GENERATED");
  }

  @Test
  void opsCannotReadReport_supportForbiddenOnFloat() {
    assertThatThrownBy(() -> service.reconciliationReport(ops, LocalDate.parse("2026-07-23")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.floatSummary(support, null, false, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void onDepositConfirmed_writesLedgerOnce() {
    UUID depositId = UUID.randomUUID();
    when(floats.hasCodDepositLedgerEntry(depositId)).thenReturn(false);
    service.onDepositConfirmed(depositId, riderId, 50_000L);
    verify(ledger)
        .append(
            eq("COD_DEPOSIT"), eq(depositId), eq("COD_DEPOSIT"), eq(50_000L), eq(0L), any(), any());

    when(floats.hasCodDepositLedgerEntry(depositId)).thenReturn(true);
    service.onDepositConfirmed(depositId, riderId, 50_000L);
    verify(ledger).append(any(), any(), any(), anyLong(), anyLong(), any(), any());
  }

  @Test
  void onDepositConfirmed_skipsInvalid() {
    service.onDepositConfirmed(null, riderId, 10);
    service.onDepositConfirmed(UUID.randomUUID(), riderId, 0);
    verify(ledger, never()).append(any(), any(), any(), anyLong(), anyLong(), any(), any());
  }

  @Test
  void riskStatusHelper() {
    assertThat(CodFloatFacadeService.riskStatus(200_001L, 200_000L)).isEqualTo("FLOAT_RISK");
    assertThat(CodFloatFacadeService.riskStatus(200_000L, 200_000L)).isEqualTo("SAFE");
  }

  @Test
  void pendingReportTreatedAsNotGenerated() {
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
                    "PENDING",
                    false,
                    NOW,
                    null,
                    "[]")));
    assertThatThrownBy(() -> service.reconciliationReport(finance, LocalDate.parse("2026-07-23")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REPORT_NOT_GENERATED");
  }
}

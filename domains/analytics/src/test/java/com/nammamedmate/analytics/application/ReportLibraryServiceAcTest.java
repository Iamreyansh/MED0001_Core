package com.nammamedmate.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.analytics.application.port.out.AdminReportStore;
import com.nammamedmate.analytics.application.port.out.AdminReportStore.HistoryRow;
import com.nammamedmate.analytics.application.port.out.AdminReportStore.JobRow;
import com.nammamedmate.analytics.application.port.out.AdminReportStore.ReportDefinition;
import com.nammamedmate.analytics.application.port.out.AdminReportStore.ReportRows;
import com.nammamedmate.analytics.application.port.out.AnalyticsExportPort;
import com.nammamedmate.analytics.application.port.out.ReportAuditPort;
import com.nammamedmate.analytics.application.port.out.ReportDeliveryEmailPort;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Duration;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportLibraryServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T01:30:00Z");

  @Mock private AdminReportStore store;
  @Mock private AnalyticsExportPort exports;
  @Mock private ReportAuditPort audit;
  @Mock private ReportDeliveryEmailPort email;

  private ReportLibraryService service;
  private MedmatePrincipal finance;
  private MedmatePrincipal ops;
  private MedmatePrincipal compliance;
  private MedmatePrincipal support;
  private MedmatePrincipal superAdmin;

  @BeforeEach
  void setUp() {
    service =
        new ReportLibraryService(
            store, exports, audit, email, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    finance =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    compliance =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    support =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    superAdmin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  }

  @Test
  void ac001_asyncTrueOver10kReturnsQueuedJob() {
    when(store.findDefinition("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(Optional.of(financeDef("GMV_COMMISSION_PAYOUTS")));
    when(store.countActiveJobs(finance.subject())).thenReturn(0);
    when(store.estimateRows(eq("GMV_COMMISSION_PAYOUTS"), any(), any(), anyMap()))
        .thenReturn(15_420L);

    var result =
        service.generate(
            finance,
            "GMV_COMMISSION_PAYOUTS",
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            Map.of(),
            "CSV",
            true);

    assertThat(result.asyncAccepted()).isTrue();
    assertThat(result.data().get("status")).isEqualTo("QUEUED");
    assertThat(result.data().get("estimated_rows")).isEqualTo(15_420L);
    assertThat(result.data().get("job_id")).isNotNull();
    verify(store).insertJob(any());
  }

  @Test
  void ac002_completedJobReturnsDownloadUrlAnd7DayExpiry() {
    UUID jobId = UUID.randomUUID();
    Instant completed = NOW;
    Instant expires = completed.plus(Duration.ofDays(7));
    JobRow job =
        new JobRow(
            jobId,
            "GMV_COMMISSION_PAYOUTS",
            finance.subject(),
            "MANUAL",
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            "{}",
            "CSV",
            "COMPLETED",
            100,
            15420,
            2840,
            "reports/gmv.csv",
            null,
            expires,
            NOW,
            NOW,
            completed,
            null);
    when(store.findTimedOutJobIds(any())).thenReturn(List.of());
    when(store.findJob(jobId)).thenReturn(Optional.of(job));
    when(store.findDefinition("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "GMV_COMMISSION_PAYOUTS", "GMV", "FINANCE", "d", "MONTHLY", "CSV", 2, true)));
    when(exports.signedGet(eq("reports/gmv.csv"), eq(Duration.ofDays(7))))
        .thenReturn(new AnalyticsExportPort.SignedUrl("https://s3.example/gmv.csv", expires));

    Map<String, Object> data = service.jobStatus(finance, jobId);

    assertThat(data.get("download_url")).isEqualTo("https://s3.example/gmv.csv");
    assertThat(Instant.parse((String) data.get("expires_at"))).isEqualTo(expires);
    assertThat(Duration.between(completed, expires).toDays()).isEqualTo(7);
  }

  @Test
  void ac003_scheduleEnabledWithoutRecipientsMissingRecipients() {
    when(store.findDefinition("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(Optional.of(financeDef("GMV_COMMISSION_PAYOUTS")));
    assertThatThrownBy(
            () ->
                service.updateSchedule(
                    finance, "GMV_COMMISSION_PAYOUTS", true, "WEEKLY", List.of(), "CSV"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_RECIPIENTS");
  }

  @Test
  void ac004_financeCannotGenerateCompliance() {
    when(store.findDefinition("COMPLIANCE_SCHEDULE_H"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "COMPLIANCE_SCHEDULE_H",
                    "Schedule H",
                    "COMPLIANCE",
                    "d",
                    "ON_DEMAND",
                    "PDF",
                    5,
                    true)));
    assertThatThrownBy(
            () ->
                service.generate(
                    finance,
                    "COMPLIANCE_SCHEDULE_H",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31),
                    Map.of(),
                    "PDF",
                    false))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void ac005_historyShowsSchedulerForScheduledRuns() {
    UUID jobId = UUID.randomUUID();
    JobRow job =
        new JobRow(
            jobId,
            "GMV_COMMISSION_PAYOUTS",
            null,
            "SCHEDULED",
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            "{}",
            "CSV",
            "COMPLETED",
            100,
            10,
            1,
            "reports/x.csv",
            "url",
            NOW.plus(Duration.ofDays(7)),
            NOW,
            NOW,
            NOW,
            null);
    when(store.listHistory(isNull(), eq(NOW), anyInt(), anyInt()))
        .thenReturn(List.of(new HistoryRow(job, "GMV", "FINANCE", "SCHEDULER")));
    when(store.countHistory(isNull(), eq(NOW))).thenReturn(1L);
    when(exports.signedGet(anyString(), any()))
        .thenReturn(new AnalyticsExportPort.SignedUrl("url", NOW.plus(Duration.ofDays(7))));

    var result = service.history(superAdmin, null, 1, 20);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> history = (List<Map<String, Object>>) result.data().get("history");
    assertThat(history.getFirst().get("generated_by")).isEqualTo("SCHEDULER");
  }

  @Test
  void ac006_complianceHistoryVisibleBeyondTwoYears() {
    Instant generated = NOW.minus(Duration.ofDays(800));
    UUID jobId = UUID.randomUUID();
    JobRow job =
        new JobRow(
            jobId,
            "COMPLIANCE_SCHEDULE_H",
            compliance.subject(),
            "MANUAL",
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 1, 31),
            "{}",
            "PDF",
            "COMPLETED",
            100,
            5,
            1,
            "reports/h.csv",
            "url",
            generated.plus(Duration.ofDays(7)),
            generated,
            generated,
            generated,
            null);
    when(store.listHistory(eq("COMPLIANCE"), eq(NOW), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new HistoryRow(job, "Schedule H", "COMPLIANCE", compliance.subject().toString())));
    when(store.countHistory(eq("COMPLIANCE"), eq(NOW))).thenReturn(1L);
    when(exports.signedGet(anyString(), any()))
        .thenReturn(new AnalyticsExportPort.SignedUrl("url", NOW.plus(Duration.ofDays(7))));

    var result = service.history(compliance, "COMPLIANCE", 1, 20);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> history = (List<Map<String, Object>>) result.data().get("history");
    assertThat(history).hasSize(1);
    assertThat(history.getFirst().get("category")).isEqualTo("COMPLIANCE");
  }

  @Test
  void ac007_taxGstr8ReconcilesWithLedgerZeroTolerance() {
    when(store.findDefinition("TAX_GSTR8_PREP"))
        .thenReturn(Optional.of(financeDef("TAX_GSTR8_PREP")));
    when(store.countActiveJobs(finance.subject())).thenReturn(0);
    when(store.estimateRows(eq("TAX_GSTR8_PREP"), any(), any(), anyMap())).thenReturn(2L);
    when(store.generateRows(eq("TAX_GSTR8_PREP"), any(), any(), anyMap()))
        .thenReturn(
            new ReportRows(
                List.of("tcs_collected_paise"), List.of(List.of("100"), List.of("50")), 150L));
    when(store.ledgerTcsTotalPaise(any(), any())).thenReturn(150L);
    when(exports.signedGet(anyString(), any()))
        .thenReturn(
            new AnalyticsExportPort.SignedUrl(
                "https://s3/gstr8.csv", NOW.plus(Duration.ofDays(7))));

    var result =
        service.generate(
            finance,
            "TAX_GSTR8_PREP",
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            Map.of(),
            "CSV",
            false);

    assertThat(result.asyncAccepted()).isFalse();
    assertThat(result.data().get("status")).isEqualTo("COMPLETED");
    verify(store).ledgerTcsTotalPaise(any(), any());
  }

  @Test
  void ac008_jobTimeoutMarkedFailed() {
    UUID jobId = UUID.randomUUID();
    when(store.findTimedOutJobIds(NOW.minus(Duration.ofHours(24)))).thenReturn(List.of(jobId));

    service.expireTimedOutJobs();

    verify(store).markJobFailed(jobId, "JOB_TIMEOUT", NOW);
  }

  @Test
  void ac009_generationWritesAuditWithActorAndRowCount() {
    when(store.findDefinition("COHORT_RETENTION"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "COHORT_RETENTION", "Cohort", "GROWTH", "d", "WEEKLY", "CSV", 2, true)));
    when(store.countActiveJobs(ops.subject())).thenReturn(0);
    when(store.estimateRows(eq("COHORT_RETENTION"), any(), any(), anyMap())).thenReturn(10L);
    when(store.generateRows(eq("COHORT_RETENTION"), any(), any(), anyMap()))
        .thenReturn(new ReportRows(List.of("a"), List.of(List.of("1")), 0L));
    when(exports.signedGet(anyString(), any()))
        .thenReturn(
            new AnalyticsExportPort.SignedUrl("https://s3/c.csv", NOW.plus(Duration.ofDays(7))));

    service.generate(
        ops,
        "COHORT_RETENTION",
        LocalDate.of(2026, 7, 1),
        LocalDate.of(2026, 7, 31),
        Map.of(),
        "CSV",
        false);

    ArgumentCaptor<Integer> rows = ArgumentCaptor.forClass(Integer.class);
    verify(audit)
        .recordGeneration(
            eq(ops.subject()),
            anyString(),
            anyString(),
            eq("COHORT_RETENTION"),
            any(),
            anyString(),
            anyString(),
            rows.capture(),
            anyString(),
            eq(NOW));
    assertThat(rows.getValue()).isEqualTo(1);
  }

  @Test
  void processJobAuditsScheduledAsScheduler() {
    UUID jobId = UUID.randomUUID();
    JobRow queued =
        new JobRow(
            jobId,
            "GMV_COMMISSION_PAYOUTS",
            null,
            "SCHEDULED",
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 7),
            "{}",
            "CSV",
            "QUEUED",
            0,
            null,
            null,
            null,
            null,
            null,
            NOW,
            null,
            null,
            null);
    when(store.findJob(jobId)).thenReturn(Optional.of(queued));
    when(store.generateRows(anyString(), any(), any(), anyMap()))
        .thenReturn(new ReportRows(List.of("x"), List.of(List.of("1")), 0L));
    when(exports.signedGet(anyString(), any()))
        .thenReturn(new AnalyticsExportPort.SignedUrl("u", NOW.plus(Duration.ofDays(7))));

    service.processJob(jobId);

    verify(audit)
        .recordGeneration(
            isNull(),
            eq("SCHEDULER"),
            eq("SYSTEM"),
            eq("GMV_COMMISSION_PAYOUTS"),
            eq(jobId),
            anyString(),
            anyString(),
            eq(1),
            anyString(),
            any());
  }

  private static ReportDefinition financeDef(String id) {
    return new ReportDefinition(id, id, "FINANCE", "d", "MONTHLY", "CSV", 2, true);
  }
}

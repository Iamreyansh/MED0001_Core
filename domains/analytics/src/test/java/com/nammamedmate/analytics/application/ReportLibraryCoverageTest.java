package com.nammamedmate.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.analytics.application.port.out.AdminReportStore;
import com.nammamedmate.analytics.application.port.out.AdminReportStore.JobRow;
import com.nammamedmate.analytics.application.port.out.AdminReportStore.ReportDefinition;
import com.nammamedmate.analytics.application.port.out.AdminReportStore.ReportRows;
import com.nammamedmate.analytics.application.port.out.AdminReportStore.ScheduleRow;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportLibraryCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T01:30:00Z");

  @Mock private AdminReportStore store;
  @Mock private AnalyticsExportPort exports;
  @Mock private ReportAuditPort audit;
  @Mock private ReportDeliveryEmailPort email;

  private ReportLibraryService service;
  private MedmatePrincipal finance;
  private MedmatePrincipal superAdmin;
  private MedmatePrincipal compliance;
  private MedmatePrincipal support;

  @BeforeEach
  void setUp() {
    service =
        new ReportLibraryService(
            store, exports, audit, email, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    finance =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    superAdmin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    compliance =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    support =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
  }

  @Test
  void listReportsAndScheduleAndDueRuns() {
    when(store.listDefinitions(null))
        .thenReturn(
            List.of(
                new ReportDefinition(
                    "GMV_COMMISSION_PAYOUTS", "GMV", "FINANCE", "d", "MONTHLY", "CSV", 2, true),
                new ReportDefinition(
                    "COHORT_RETENTION", "Cohort", "GROWTH", "d", "WEEKLY", "CSV", 2, true)));
    when(store.findSchedule("GMV_COMMISSION_PAYOUTS")).thenReturn(Optional.empty());
    when(store.lastCompletedAt(anyString())).thenReturn(null);

    Map<String, Object> listed = service.listReports(finance, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> reports = (List<Map<String, Object>>) listed.get("reports");
    assertThat(reports).hasSize(1);

    when(store.findDefinition("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "GMV_COMMISSION_PAYOUTS", "GMV", "FINANCE", "d", "MONTHLY", "CSV", 2, true)));
    when(store.findSchedule("GMV_COMMISSION_PAYOUTS")).thenReturn(Optional.empty());
    Map<String, Object> schedule =
        service.updateSchedule(
            finance,
            "GMV_COMMISSION_PAYOUTS",
            true,
            "WEEKLY",
            List.of("finance@nammamedmate.in"),
            "CSV");
    assertThat(schedule.get("is_scheduled_enabled")).isEqualTo(true);
    verify(store).upsertSchedule(any());

    UUID scheduleId = UUID.randomUUID();
    when(store.findDueSchedules(NOW))
        .thenReturn(
            List.of(
                new ScheduleRow(
                    scheduleId,
                    "GMV_COMMISSION_PAYOUTS",
                    true,
                    "WEEKLY",
                    "CSV",
                    List.of("a@b.com"),
                    NOW,
                    finance.subject(),
                    NOW)));
    when(store.findDefinition("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "GMV_COMMISSION_PAYOUTS", "GMV", "FINANCE", "d", "MONTHLY", "CSV", 2, true)));
    UUID jobId = UUID.randomUUID();
    // processJob path via findJob after insert — use Answer to return queued then completed
    when(store.findJob(any()))
        .thenAnswer(
            inv -> {
              UUID id = inv.getArgument(0);
              return Optional.of(
                  new JobRow(
                      id,
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
                      null));
            });
    when(store.generateRows(anyString(), any(), any(), anyMap()))
        .thenReturn(new ReportRows(List.of("a"), List.of(List.of("1")), 0L));
    when(exports.signedGet(anyString(), any()))
        .thenReturn(new AnalyticsExportPort.SignedUrl("u", NOW.plus(Duration.ofDays(7))));

    // After processJob, second findJob for email — return completed then empty for continue path
    when(store.findJob(any()))
        .thenReturn(
            Optional.of(
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
                    null)))
        .thenReturn(
            Optional.of(
                new JobRow(
                    jobId,
                    "GMV_COMMISSION_PAYOUTS",
                    null,
                    "SCHEDULED",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 7),
                    "{}",
                    "CSV",
                    "COMPLETED",
                    100,
                    1,
                    1,
                    "k",
                    "u",
                    NOW.plus(Duration.ofDays(7)),
                    NOW,
                    NOW,
                    NOW,
                    null)));

    service.runDueSchedules();
    verify(email).sendScheduledReport(any(), eq("GMV_COMMISSION_PAYOUTS"), eq("CSV"), any(), any());

    when(store.findDueSchedules(NOW))
        .thenReturn(
            List.of(
                new ScheduleRow(
                    scheduleId,
                    "GMV_COMMISSION_PAYOUTS",
                    true,
                    "WEEKLY",
                    "CSV",
                    List.of("a@b.com"),
                    NOW,
                    finance.subject(),
                    NOW)));
    when(store.findJob(any()))
        .thenReturn(
            Optional.of(
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
                    null)))
        .thenReturn(Optional.empty());
    service.runDueSchedules();
  }

  @Test
  void errorsAndTimeoutsAndJobOwnership() {
    assertThatThrownBy(() -> service.listReports(null, null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    when(store.findDefinition("NOPE")).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.generate(
                    finance, "NOPE", LocalDate.now(), LocalDate.now(), Map.of(), "CSV", true))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REPORT_NOT_FOUND");

    when(store.findDefinition("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "GMV_COMMISSION_PAYOUTS", "GMV", "FINANCE", "d", "MONTHLY", "CSV", 2, true)));
    assertThatThrownBy(
            () ->
                service.generate(
                    finance,
                    "GMV_COMMISSION_PAYOUTS",
                    LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 7, 1),
                    Map.of(),
                    "CSV",
                    true))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD_RANGE");

    when(store.countActiveJobs(finance.subject())).thenReturn(5);
    assertThatThrownBy(
            () ->
                service.generate(
                    finance,
                    "GMV_COMMISSION_PAYOUTS",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31),
                    Map.of(),
                    "CSV",
                    true))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("TOO_MANY_JOBS");

    when(store.findDefinition("COMPLIANCE_SCHEDULE_H"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "COMPLIANCE_SCHEDULE_H", "H", "COMPLIANCE", "d", "ON_DEMAND", "PDF", 5, true)));
    MedmatePrincipal compliance =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    assertThatThrownBy(
            () ->
                service.updateSchedule(
                    compliance,
                    "COMPLIANCE_SCHEDULE_H",
                    true,
                    "ON_DEMAND",
                    List.of("a@b.com"),
                    "PDF"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CADENCE");
    assertThatThrownBy(
            () ->
                service.updateSchedule(
                    compliance, "COMPLIANCE_SCHEDULE_H", true, null, List.of("a@b.com"), "PDF"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CADENCE");
    service.updateSchedule(
        compliance, "COMPLIANCE_SCHEDULE_H", true, "WEEKLY", List.of("a@b.com"), "PDF");

    when(store.findTimedOutJobIds(any())).thenReturn(List.of());
    UUID missingJob = UUID.randomUUID();
    when(store.findJob(missingJob)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.jobStatus(finance, missingJob))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("JOB_NOT_FOUND");

    UUID orphan = UUID.randomUUID();
    when(store.findJob(orphan))
        .thenReturn(
            Optional.of(
                new JobRow(
                    orphan,
                    "MISSING_REPORT",
                    finance.subject(),
                    "MANUAL",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 2),
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
                    null)));
    when(store.findDefinition("MISSING_REPORT")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.jobStatus(finance, orphan))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REPORT_NOT_FOUND");

    UUID other = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    when(store.findJob(jobId))
        .thenReturn(
            Optional.of(
                new JobRow(
                    jobId,
                    "GMV_COMMISSION_PAYOUTS",
                    other,
                    "MANUAL",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 2),
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
                    null)));
    when(store.findDefinition("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "GMV_COMMISSION_PAYOUTS", "GMV", "FINANCE", "d", "MONTHLY", "CSV", 2, true)));
    assertThatThrownBy(() -> service.jobStatus(finance, jobId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    UUID scheduledCompliance = UUID.randomUUID();
    when(store.findJob(scheduledCompliance))
        .thenReturn(
            Optional.of(
                new JobRow(
                    scheduledCompliance,
                    "COMPLIANCE_SCHEDULE_H",
                    null,
                    "SCHEDULED",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 2),
                    "{}",
                    "PDF",
                    "COMPLETED",
                    100,
                    10,
                    1,
                    "reports/h.pdf",
                    "old",
                    NOW,
                    NOW,
                    NOW,
                    NOW,
                    null)));
    when(store.findDefinition("COMPLIANCE_SCHEDULE_H"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "COMPLIANCE_SCHEDULE_H", "H", "COMPLIANCE", "d", "ON_DEMAND", "PDF", 5, true)));
    assertThatThrownBy(() -> service.jobStatus(finance, scheduledCompliance))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThat(service.jobStatus(support, scheduledCompliance).get("download_url")).isNull();
    when(exports.signedGet(eq("reports/h.pdf"), any()))
        .thenReturn(new AnalyticsExportPort.SignedUrl("https://signed", NOW.plusSeconds(3600)));
    assertThat(service.jobStatus(compliance, scheduledCompliance).get("download_url"))
        .isEqualTo("https://signed");

    when(store.findQueuedJobIds(5)).thenReturn(List.of(jobId));
    when(store.findJob(jobId))
        .thenReturn(
            Optional.of(
                new JobRow(
                    jobId,
                    "TAX_GSTR8_PREP",
                    finance.subject(),
                    "MANUAL",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31),
                    "{bad",
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
                    null)));
    when(store.generateRows(eq("TAX_GSTR8_PREP"), any(), any(), anyMap()))
        .thenReturn(new ReportRows(List.of("t"), List.of(List.of("1")), 99L));
    when(store.ledgerTcsTotalPaise(any(), any())).thenReturn(1L);
    service.processQueuedBatch(5);
    verify(store).markJobFailed(eq(jobId), eq("GSTR8_RECONCILE_FAILED"), any());

    assertThat(ReportLibraryService.canAccessCategory(superAdmin, "FINANCE")).isTrue();
  }

  @Test
  void processorDelegates() {
    AdminReportJobProcessor processor = new AdminReportJobProcessor(service);
    when(store.findTimedOutJobIds(any())).thenReturn(List.of());
    when(store.findQueuedJobIds(5)).thenReturn(List.of());
    when(store.findDueSchedules(any())).thenReturn(List.of());
    processor.pollQueued();
    processor.runSchedules();
    verify(store).findQueuedJobIds(5);
    verify(store).findDueSchedules(NOW);
  }
}

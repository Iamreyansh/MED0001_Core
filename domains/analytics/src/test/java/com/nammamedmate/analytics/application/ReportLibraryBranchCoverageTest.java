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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportLibraryBranchCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T01:30:00Z");

  @Mock AdminReportStore store;
  @Mock AnalyticsExportPort exports;
  @Mock ReportAuditPort audit;
  @Mock ReportDeliveryEmailPort email;

  ReportLibraryService service;
  MedmatePrincipal finance;
  MedmatePrincipal ops;
  MedmatePrincipal support;
  MedmatePrincipal compliance;
  MedmatePrincipal superAdmin;

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
    support =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
    compliance =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    superAdmin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    when(exports.signedGet(anyString(), any()))
        .thenReturn(new AnalyticsExportPort.SignedUrl("https://u", NOW.plus(Duration.ofDays(7))));
  }

  @Test
  void listCategoryFilterAndSupportForbidden() {
    when(store.listDefinitions("FINANCE"))
        .thenReturn(
            List.of(
                new ReportDefinition(
                    "GMV_COMMISSION_PAYOUTS", "GMV", "FINANCE", "d", "MONTHLY", "CSV", 2, true)));
    when(store.findSchedule(anyString())).thenReturn(Optional.empty());
    when(store.lastCompletedAt(anyString())).thenReturn(NOW);
    assertThat(service.listReports(finance, "FINANCE")).containsKey("reports");

    assertThatThrownBy(() -> service.listReports(support, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.listReports(finance, "COMPLIANCE"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void syncForceAsyncFalseAndFormatFallbacks() {
    when(store.findDefinition("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "GMV_COMMISSION_PAYOUTS", "GMV", "FINANCE", "d", "MONTHLY", "CSV", 2, true)));
    when(store.countActiveJobs(any())).thenReturn(0);
    when(store.estimateRows(anyString(), any(), any(), anyMap())).thenReturn(5L);
    when(store.generateRows(anyString(), any(), any(), anyMap()))
        .thenReturn(
            new ReportRows(
                List.of("a", "b"),
                List.of(java.util.Arrays.asList("1", null), List.of("x", "y\nz")),
                0L));
    var sync =
        service.generate(
            finance,
            "GMV_COMMISSION_PAYOUTS",
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 2),
            null,
            "BAD",
            false);
    assertThat(sync.asyncAccepted()).isFalse();

    assertThatThrownBy(
            () ->
                service.generate(
                    finance,
                    "GMV_COMMISSION_PAYOUTS",
                    null,
                    LocalDate.of(2026, 7, 2),
                    Map.of(),
                    "CSV",
                    null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD_RANGE");

    when(store.estimateRows(anyString(), any(), any(), anyMap())).thenReturn(20_000L);
    var forced =
        service.generate(
            finance,
            "GMV_COMMISSION_PAYOUTS",
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 2),
            Map.of("zone_id", "z"),
            "PDF",
            false);
    assertThat(forced.asyncAccepted()).isTrue();
  }

  @Test
  void scheduleDisableExistingAndInvalidCadenceAndHistoryFilters() {
    when(store.findDefinition("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "GMV_COMMISSION_PAYOUTS", "GMV", "FINANCE", "d", "MONTHLY", "CSV", 2, true)));
    when(store.findSchedule("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(
            Optional.of(
                new ScheduleRow(
                    UUID.randomUUID(),
                    "GMV_COMMISSION_PAYOUTS",
                    true,
                    "DAILY",
                    "CSV",
                    List.of("a@b.com"),
                    NOW,
                    finance.subject(),
                    NOW)));
    assertThat(
            service.updateSchedule(
                finance, "GMV_COMMISSION_PAYOUTS", false, "DAILY", List.of(), null))
        .containsEntry("is_scheduled_enabled", false);

    assertThatThrownBy(
            () ->
                service.updateSchedule(
                    finance, "GMV_COMMISSION_PAYOUTS", true, "YEARLY", List.of("a@b.com"), "CSV"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CADENCE");

    when(store.findDefinition("MISSING")).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.updateSchedule(
                    finance, "MISSING", true, "WEEKLY", List.of("a@b.com"), "CSV"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REPORT_NOT_FOUND");

    JobRow job =
        new JobRow(
            UUID.randomUUID(),
            "GMV_COMMISSION_PAYOUTS",
            finance.subject(),
            "MANUAL",
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 2),
            "{}",
            "CSV",
            "FAILED",
            100,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW,
            NOW,
            "boom");
    when(store.listHistory(eq("FINANCE"), eq(NOW), anyInt(), anyInt()))
        .thenReturn(List.of(new HistoryRow(job, "GMV", "FINANCE", "admin")));
    when(store.countHistory(eq("FINANCE"), eq(NOW))).thenReturn(1L);
    assertThat(service.history(finance, "FINANCE", 0, 0).data()).containsKey("history");
    assertThatThrownBy(() -> service.history(finance, "COMPLIANCE", 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThat(service.history(support, "FINANCE", 1, 200).meta().limit()).isEqualTo(100);
  }

  @Test
  void processJobSkipAndRuntimeFailureAndGstr8SyncFail() {
    service.processJob(UUID.randomUUID());
    when(store.findJob(any())).thenReturn(Optional.empty());
    service.processJob(UUID.randomUUID());

    UUID jobId = UUID.randomUUID();
    when(store.findJob(jobId))
        .thenReturn(
            Optional.of(
                new JobRow(
                    jobId,
                    "GMV_COMMISSION_PAYOUTS",
                    finance.subject(),
                    "MANUAL",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 2),
                    null,
                    "CSV",
                    "RUNNING",
                    10,
                    null,
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    NOW,
                    null,
                    null)));
    service.processJob(jobId);

    when(store.findJob(jobId))
        .thenReturn(
            Optional.of(
                new JobRow(
                    jobId,
                    "GMV_COMMISSION_PAYOUTS",
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
    when(store.generateRows(anyString(), any(), any(), anyMap()))
        .thenThrow(new RuntimeException("boom"));
    service.processJob(jobId);
    verify(store).markJobFailed(eq(jobId), eq("boom"), any());

    when(store.findDefinition("TAX_GSTR8_PREP"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "TAX_GSTR8_PREP", "GSTR8", "FINANCE", "d", "MONTHLY", "CSV", 2, true)));
    when(store.countActiveJobs(any())).thenReturn(0);
    when(store.estimateRows(eq("TAX_GSTR8_PREP"), any(), any(), anyMap())).thenReturn(1L);
    when(store.generateRows(eq("TAX_GSTR8_PREP"), any(), any(), anyMap()))
        .thenReturn(new ReportRows(List.of("t"), List.of(List.of("1")), 10L));
    when(store.ledgerTcsTotalPaise(any(), any())).thenReturn(0L);
    assertThatThrownBy(
            () ->
                service.generate(
                    finance,
                    "TAX_GSTR8_PREP",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31),
                    Map.of(),
                    "CSV",
                    false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("GSTR8_RECONCILE_FAILED");
  }

  @Test
  void dueSchedulesDailyMonthlyAndMissingDefAndIncompleteJob() {
    when(store.findDueSchedules(NOW))
        .thenReturn(
            List.of(
                new ScheduleRow(
                    UUID.randomUUID(),
                    "MISSING",
                    true,
                    "DAILY",
                    "CSV",
                    List.of("a@b.com"),
                    NOW,
                    null,
                    NOW),
                new ScheduleRow(
                    UUID.randomUUID(),
                    "SLA_BREACHES",
                    true,
                    "DAILY",
                    "CSV",
                    List.of("a@b.com"),
                    NOW,
                    ops.subject(),
                    NOW),
                new ScheduleRow(
                    UUID.randomUUID(),
                    "GMV_COMMISSION_PAYOUTS",
                    true,
                    "MONTHLY",
                    "CSV",
                    List.of("a@b.com"),
                    NOW,
                    finance.subject(),
                    NOW)));
    when(store.findDefinition("MISSING")).thenReturn(Optional.empty());
    when(store.findDefinition("SLA_BREACHES"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "SLA_BREACHES", "SLA", "OPERATIONS", "d", "DAILY", "CSV", 2, true)));
    when(store.findDefinition("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "GMV_COMMISSION_PAYOUTS", "GMV", "FINANCE", "d", "MONTHLY", "CSV", 2, true)));
    UUID jobId = UUID.randomUUID();
    when(store.findJob(any()))
        .thenReturn(
            Optional.of(
                new JobRow(
                    jobId,
                    "SLA_BREACHES",
                    null,
                    "SCHEDULED",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 1),
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
                    "SLA_BREACHES",
                    null,
                    "SCHEDULED",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 1),
                    "{}",
                    "CSV",
                    "FAILED",
                    100,
                    null,
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    NOW,
                    NOW,
                    "x")));
    when(store.generateRows(anyString(), any(), any(), anyMap()))
        .thenReturn(new ReportRows(List.of("a"), List.of(List.of("1")), 0L));
    service.runDueSchedules();
  }

  @Test
  void jobStatusBranchesAndCategoryAccess() {
    when(store.findTimedOutJobIds(any())).thenReturn(List.of());
    UUID jobId = UUID.randomUUID();
    when(store.findDefinition("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "GMV_COMMISSION_PAYOUTS", "GMV", "FINANCE", "d", "MONTHLY", "CSV", 2, true)));
    when(store.findJob(jobId))
        .thenReturn(
            Optional.of(
                new JobRow(
                    jobId,
                    "GMV_COMMISSION_PAYOUTS",
                    finance.subject(),
                    "MANUAL",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 2),
                    "{}",
                    "CSV",
                    "RUNNING",
                    50,
                    null,
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    NOW,
                    null,
                    null)));
    assertThat(service.jobStatus(superAdmin, jobId).get("status")).isEqualTo("RUNNING");
    assertThat(service.jobStatus(finance, jobId).get("progress_pct")).isEqualTo(50);

    UUID gstrJob = UUID.randomUUID();
    when(store.findJob(gstrJob))
        .thenReturn(
            Optional.of(
                new JobRow(
                    gstrJob,
                    "TAX_GSTR8_PREP",
                    finance.subject(),
                    "MANUAL",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31),
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
    when(store.generateRows(eq("TAX_GSTR8_PREP"), any(), any(), anyMap()))
        .thenReturn(new ReportRows(List.of("t"), List.of(List.of("1")), 10L));
    when(store.ledgerTcsTotalPaise(any(), any())).thenReturn(10L);
    service.processJob(gstrJob);
    assertThat(ReportLibraryService.needsCsvQuote(",")).isTrue();
    assertThat(ReportLibraryService.needsCsvQuote("\"")).isTrue();
    assertThat(ReportLibraryService.needsCsvQuote("\n")).isTrue();
    assertThat(ReportLibraryService.needsCsvQuote("ok")).isFalse();
    assertThat(ReportLibraryService.canAccessCategory(compliance, "COMPLIANCE")).isTrue();
    assertThat(ReportLibraryService.canAccessCategory(support, "FINANCE")).isTrue();
    assertThat(ReportLibraryService.canAccessCategory(ops, "OPERATIONS")).isTrue();
    assertThat(ReportLibraryService.canAccessCategory(ops, "GROWTH")).isTrue();
    assertThat(ReportLibraryService.canAccessCategory(ops, "FINANCE")).isFalse();
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThat(ReportLibraryService.canAccessCategory(customer, "FINANCE")).isFalse();
    assertThatThrownBy(() -> service.history(customer, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
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
                    LocalDate.of(2026, 7, 1),
                    null,
                    Map.of(),
                    "CSV",
                    false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PERIOD_RANGE");

    UUID schedJob = UUID.randomUUID();
    when(store.findTimedOutJobIds(any())).thenReturn(List.of());
    when(store.findJob(schedJob))
        .thenReturn(
            Optional.of(
                new JobRow(
                    schedJob,
                    "GMV_COMMISSION_PAYOUTS",
                    null,
                    "SCHEDULED",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 2),
                    "{}",
                    "CSV",
                    "COMPLETED",
                    100,
                    1,
                    1,
                    null,
                    null,
                    null,
                    NOW,
                    NOW,
                    NOW,
                    null)));
    when(store.findDefinition("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "GMV_COMMISSION_PAYOUTS", "GMV", "FINANCE", "d", "MONTHLY", "CSV", 2, true)));
    assertThat(service.jobStatus(finance, schedJob).get("download_url")).isNull();

    UUID complianceSched = UUID.randomUUID();
    when(store.findJob(complianceSched))
        .thenReturn(
            Optional.of(
                new JobRow(
                    complianceSched,
                    "COMPLIANCE_SCHEDULE_H",
                    null,
                    "SCHEDULED",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 2),
                    "{}",
                    "PDF",
                    "COMPLETED",
                    100,
                    1,
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
    assertThatThrownBy(() -> service.jobStatus(finance, complianceSched))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThat(service.jobStatus(support, complianceSched).get("download_url")).isNull();

    when(store.listHistory(eq("COMPLIANCE"), eq(NOW), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new HistoryRow(
                    new JobRow(
                        complianceSched,
                        "COMPLIANCE_SCHEDULE_H",
                        null,
                        "SCHEDULED",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 2),
                        "{}",
                        "PDF",
                        "COMPLETED",
                        100,
                        1,
                        1,
                        "reports/h.pdf",
                        "old-url",
                        NOW,
                        NOW,
                        NOW,
                        NOW,
                        null),
                    "Schedule H",
                    "COMPLIANCE",
                    "SCHEDULER")));
    when(store.countHistory(eq("COMPLIANCE"), eq(NOW))).thenReturn(1L);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> supportHistory =
        (List<Map<String, Object>>)
            service.history(support, "COMPLIANCE", 1, 20).data().get("history");
    assertThat(supportHistory.getFirst().get("download_url")).isNull();

    when(store.listHistory(eq("FINANCE"), eq(NOW), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new HistoryRow(
                    new JobRow(
                        UUID.randomUUID(),
                        "GMV_COMMISSION_PAYOUTS",
                        finance.subject(),
                        "MANUAL",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 2),
                        "{}",
                        "CSV",
                        "COMPLETED",
                        100,
                        1,
                        1,
                        "reports/gmv.csv",
                        "old",
                        NOW,
                        NOW,
                        NOW,
                        NOW,
                        null),
                    "GMV",
                    "FINANCE",
                    "fin")));
    when(store.countHistory(eq("FINANCE"), eq(NOW))).thenReturn(1L);
    when(exports.signedGet(eq("reports/gmv.csv"), any()))
        .thenReturn(new AnalyticsExportPort.SignedUrl("https://s3/gmv.csv", NOW.plusSeconds(60)));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> supportFinanceHistory =
        (List<Map<String, Object>>)
            service.history(support, "FINANCE", 1, 20).data().get("history");
    assertThat(supportFinanceHistory.getFirst().get("download_url"))
        .isEqualTo("https://s3/gmv.csv");

    when(store.findDefinition("COMPLIANCE_SCHEDULE_H"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "COMPLIANCE_SCHEDULE_H", "H", "COMPLIANCE", "d", "ON_DEMAND", "PDF", 5, true)));
    when(store.findSchedule("COMPLIANCE_SCHEDULE_H")).thenReturn(Optional.empty());
    assertThat(
            service
                .updateSchedule(
                    compliance, "COMPLIANCE_SCHEDULE_H", true, "WEEKLY", List.of("a@b.com"), "PDF")
                .get("cadence"))
        .isEqualTo("WEEKLY");

    assertThat(service.history(support, "COMPLIANCE", 2, 5).meta().page()).isEqualTo(2);
    assertThat(service.history(superAdmin, null, null, null).meta().limit()).isEqualTo(20);
    when(store.listHistory(isNull(), eq(NOW), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new HistoryRow(
                    new JobRow(
                        UUID.randomUUID(),
                        "GMV_COMMISSION_PAYOUTS",
                        finance.subject(),
                        "MANUAL",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 2),
                        "{}",
                        "CSV",
                        "COMPLETED",
                        100,
                        1,
                        1,
                        null,
                        "u",
                        null,
                        NOW,
                        NOW,
                        null,
                        null),
                    "GMV",
                    "FINANCE",
                    "fin")));
    when(store.countHistory(isNull(), eq(NOW))).thenReturn(1L);
    assertThat(service.history(superAdmin, " ", 1, 20).data().get("history")).asList().isNotEmpty();

    when(store.listHistory(isNull(), eq(NOW), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new HistoryRow(
                    new JobRow(
                        jobId,
                        "COHORT_RETENTION",
                        ops.subject(),
                        "MANUAL",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 2),
                        "{}",
                        "CSV",
                        "COMPLETED",
                        100,
                        1,
                        1,
                        "reports/x.csv",
                        "old",
                        NOW,
                        NOW,
                        NOW,
                        NOW,
                        null),
                    "Cohort",
                    "GROWTH",
                    "ops"),
                new HistoryRow(
                    new JobRow(
                        UUID.randomUUID(),
                        "GMV_COMMISSION_PAYOUTS",
                        finance.subject(),
                        "MANUAL",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 2),
                        "{}",
                        "CSV",
                        "COMPLETED",
                        100,
                        1,
                        1,
                        null,
                        null,
                        null,
                        NOW,
                        NOW,
                        NOW,
                        null),
                    "GMV",
                    "FINANCE",
                    "fin")));
    when(store.countHistory(isNull(), eq(NOW))).thenReturn(2L);
    assertThat(service.history(ops, null, 1, 20).data().get("history")).asList().hasSize(1);
  }

  @Test
  void recordsAndBrokenJsonMapper() throws Exception {
    new ScheduleRow(null, "r", false, "DAILY", "CSV", null, null, null, NOW);
    new ReportRows(null, null, 0L);
    ObjectMapper broken =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value)
              throws com.fasterxml.jackson.core.JsonProcessingException {
            throw new com.fasterxml.jackson.core.JsonProcessingException("x") {};
          }

          @Override
          public <T> T readValue(String content, Class<T> valueType)
              throws com.fasterxml.jackson.core.JsonProcessingException {
            throw new com.fasterxml.jackson.core.JsonProcessingException("x") {};
          }
        };
    ReportLibraryService svc =
        new ReportLibraryService(
            store, exports, audit, email, broken, Clock.fixed(NOW, ZoneOffset.UTC));
    when(store.findDefinition("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "GMV_COMMISSION_PAYOUTS", "GMV", "FINANCE", "d", "MONTHLY", "CSV", 2, true)));
    when(store.countActiveJobs(any())).thenReturn(0);
    when(store.estimateRows(anyString(), any(), any(), anyMap())).thenReturn(1L);
    when(store.generateRows(anyString(), any(), any(), anyMap()))
        .thenReturn(new ReportRows(List.of("a"), List.of(List.of("1")), 0L));
    svc.generate(
        finance,
        "GMV_COMMISSION_PAYOUTS",
        LocalDate.of(2026, 7, 1),
        LocalDate.of(2026, 7, 2),
        Map.of("a", 1),
        null,
        false);

    UUID jobId = UUID.randomUUID();
    when(store.findJob(jobId))
        .thenReturn(
            Optional.of(
                new JobRow(
                    jobId,
                    "GMV_COMMISSION_PAYOUTS",
                    finance.subject(),
                    "MANUAL",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 2),
                    "   ",
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
    when(store.generateRows(anyString(), any(), any(), anyMap()))
        .thenReturn(new ReportRows(List.of("a"), List.of(List.of("1")), 0L));
    svc.processJob(jobId);

    UUID nullFiltersJob = UUID.randomUUID();
    when(store.findJob(nullFiltersJob))
        .thenReturn(
            Optional.of(
                new JobRow(
                    nullFiltersJob,
                    "GMV_COMMISSION_PAYOUTS",
                    finance.subject(),
                    "MANUAL",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 2),
                    null,
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
    svc.processJob(nullFiltersJob);
  }

  @Test
  void wrapReportsWithScheduleAndOnDemandBlankCadence() {
    when(store.listDefinitions(null))
        .thenReturn(
            List.of(
                new ReportDefinition(
                    "GMV_COMMISSION_PAYOUTS", "GMV", "FINANCE", "d", "MONTHLY", "CSV", 2, true)));
    when(store.findSchedule("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(
            Optional.of(
                new ScheduleRow(
                    UUID.randomUUID(),
                    "GMV_COMMISSION_PAYOUTS",
                    true,
                    "WEEKLY",
                    "PDF",
                    List.of("a@b.com"),
                    NOW.plusSeconds(3600),
                    finance.subject(),
                    NOW)));
    when(store.lastCompletedAt("GMV_COMMISSION_PAYOUTS")).thenReturn(null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> reports =
        (List<Map<String, Object>>) service.listReports(finance, " ").get("reports");
    assertThat(reports.getFirst().get("cadence")).isEqualTo("WEEKLY");
    assertThat(reports.getFirst().get("is_scheduled_enabled")).isEqualTo(true);

    when(store.findDefinition("COMPLIANCE_SCHEDULE_H"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "COMPLIANCE_SCHEDULE_H", "H", "COMPLIANCE", "d", "ON_DEMAND", "PDF", 5, true)));
    when(store.findSchedule("COMPLIANCE_SCHEDULE_H")).thenReturn(Optional.empty());
    assertThat(
            service
                .updateSchedule(
                    compliance, "COMPLIANCE_SCHEDULE_H", true, "  ", List.of("c@x.com"), "CSV")
                .get("cadence"))
        .isEqualTo("WEEKLY");

    when(store.findDefinition("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "GMV_COMMISSION_PAYOUTS", "GMV", "FINANCE", "d", "MONTHLY", "CSV", 2, true)));
    service.updateSchedule(
        finance, "GMV_COMMISSION_PAYOUTS", true, null, List.of("a@b.com"), "CSV");

    UUID jobId = UUID.randomUUID();
    when(store.findTimedOutJobIds(any())).thenReturn(List.of());
    when(store.findJob(jobId))
        .thenReturn(
            Optional.of(
                new JobRow(
                    jobId,
                    "GMV_COMMISSION_PAYOUTS",
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
    assertThat(service.jobStatus(finance, jobId).get("started_at")).isNull();

    when(store.estimateRows(anyString(), any(), any(), anyMap())).thenReturn(3L);
    when(store.countActiveJobs(any())).thenReturn(0);
    when(store.findDefinition("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(
            Optional.of(
                new ReportDefinition(
                    "GMV_COMMISSION_PAYOUTS", "GMV", "FINANCE", "d", "MONTHLY", "CSV", 2, true)));
    assertThat(
            service
                .generate(
                    finance,
                    "GMV_COMMISSION_PAYOUTS",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 2),
                    Map.of(),
                    " ",
                    null)
                .asyncAccepted())
        .isTrue();
    assertThat(
            service
                .generate(
                    finance,
                    "GMV_COMMISSION_PAYOUTS",
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 2),
                    Map.of(),
                    "CSV",
                    true)
                .asyncAccepted())
        .isTrue();

    when(store.findSchedule("GMV_COMMISSION_PAYOUTS"))
        .thenReturn(
            Optional.of(
                new ScheduleRow(
                    UUID.randomUUID(),
                    "GMV_COMMISSION_PAYOUTS",
                    false,
                    "WEEKLY",
                    "CSV",
                    List.of(),
                    null,
                    finance.subject(),
                    NOW)));
    when(store.listDefinitions("FINANCE"))
        .thenReturn(
            List.of(
                new ReportDefinition(
                    "GMV_COMMISSION_PAYOUTS", "GMV", "FINANCE", "d", "MONTHLY", "CSV", 2, true)));
    when(store.lastCompletedAt(anyString())).thenReturn(NOW);
    assertThat(service.listReports(finance, "FINANCE")).containsKey("reports");

    service.updateSchedule(finance, "GMV_COMMISSION_PAYOUTS", null, "WEEKLY", null, "CSV");
  }
}

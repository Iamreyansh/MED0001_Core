package com.nammamedmate.analytics.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.ReportLibraryService;
import com.nammamedmate.analytics.application.ReportLibraryService.GenerateResult;
import com.nammamedmate.analytics.application.ReportLibraryService.HistoryResult;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AdminReportsControllerTest {

  @Mock private ReportLibraryService reports;
  @InjectMocks private AdminReportsController controller;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @Test
  void delegatesEndpoints() {
    when(reports.listReports(principal, "FINANCE")).thenReturn(Map.of("reports", List.of()));
    assertThat(controller.list(principal, "FINANCE").data()).containsKey("reports");

    when(reports.generate(
            eq(principal),
            eq("GMV_COMMISSION_PAYOUTS"),
            eq(LocalDate.of(2026, 7, 1)),
            eq(LocalDate.of(2026, 7, 31)),
            any(),
            eq("CSV"),
            eq(true)))
        .thenReturn(new GenerateResult(true, Map.of("status", "QUEUED")));
    var async =
        controller.generate(
            principal,
            "GMV_COMMISSION_PAYOUTS",
            new AdminReportsController.GenerateRequest(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), Map.of(), "CSV", true));
    assertThat(async.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    when(reports.generate(
            eq(principal), eq("COHORT_RETENTION"), any(), any(), any(), any(), eq(false)))
        .thenReturn(new GenerateResult(false, Map.of("status", "COMPLETED")));
    var sync =
        controller.generate(
            principal,
            "COHORT_RETENTION",
            new AdminReportsController.GenerateRequest(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null, "CSV", false));
    assertThat(sync.getStatusCode()).isEqualTo(HttpStatus.OK);

    UUID jobId = UUID.randomUUID();
    when(reports.jobStatus(principal, jobId)).thenReturn(Map.of("status", "RUNNING"));
    assertThat(controller.job(principal, jobId).data()).containsEntry("status", "RUNNING");

    when(reports.updateSchedule(
            eq(principal), eq("GMV_COMMISSION_PAYOUTS"), eq(true), any(), any(), any()))
        .thenReturn(Map.of("is_scheduled_enabled", true));
    assertThat(
            controller
                .schedule(
                    principal,
                    "GMV_COMMISSION_PAYOUTS",
                    new AdminReportsController.ScheduleRequest(
                        true, "WEEKLY", List.of("a@b.com"), "CSV"))
                .data())
        .containsEntry("is_scheduled_enabled", true);

    when(reports.history(principal, null, 1, 20))
        .thenReturn(new HistoryResult(Map.of("history", List.of()), PaginationMeta.of(1, 20, 0)));
    assertThat(controller.history(principal, null, 1, 20).data()).containsKey("history");

    when(reports.generate(eq(principal), eq("X"), isNull(), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(new GenerateResult(false, Map.of("status", "COMPLETED")));
    when(reports.updateSchedule(eq(principal), eq("X"), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(Map.of());
    controller.generate(principal, "X", null);
    controller.schedule(principal, "X", null);
    verify(reports).listReports(principal, "FINANCE");
  }
}

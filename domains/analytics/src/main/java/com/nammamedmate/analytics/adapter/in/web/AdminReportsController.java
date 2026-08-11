package com.nammamedmate.analytics.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.analytics.application.ReportLibraryService;
import com.nammamedmate.analytics.application.ReportLibraryService.GenerateResult;
import com.nammamedmate.analytics.application.ReportLibraryService.HistoryResult;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/reports")
@Tag(name = "Admin report library")
public class AdminReportsController {

  private final ReportLibraryService reports;

  public AdminReportsController(ReportLibraryService reports) {
    this.reports = reports;
  }

  @GetMapping
  @Operation(summary = "List admin report library")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String category) {
    return ApiResponse.ok(reports.listReports(principal, category));
  }

  @PostMapping("/{reportId}/generate")
  @Operation(summary = "Generate admin report (sync or async)")
  public ResponseEntity<ApiResponse<Map<String, Object>>> generate(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable String reportId,
      @RequestBody GenerateRequest body) {
    GenerateRequest req = body == null ? new GenerateRequest(null, null, null, null, null) : body;
    GenerateResult result =
        reports.generate(
            principal,
            reportId,
            req.periodFrom(),
            req.periodTo(),
            req.filters(),
            req.format(),
            req.async());
    HttpStatus status = result.asyncAccepted() ? HttpStatus.ACCEPTED : HttpStatus.OK;
    return ResponseEntity.status(status).body(ApiResponse.ok(result.data()));
  }

  @GetMapping("/jobs/{jobId}")
  @Operation(summary = "Poll async report job status")
  public ApiResponse<Map<String, Object>> job(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID jobId) {
    return ApiResponse.ok(reports.jobStatus(principal, jobId));
  }

  @PatchMapping("/{reportId}/schedule")
  @Operation(summary = "Update report schedule")
  public ApiResponse<Map<String, Object>> schedule(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable String reportId,
      @RequestBody ScheduleRequest body) {
    ScheduleRequest req = body == null ? new ScheduleRequest(null, null, null, null) : body;
    return ApiResponse.ok(
        reports.updateSchedule(
            principal,
            reportId,
            req.enabled(),
            req.cadence(),
            req.emailRecipients(),
            req.format()));
  }

  @GetMapping("/history")
  @Operation(summary = "List generated report history")
  public ApiResponse<Map<String, Object>> history(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    HistoryResult result = reports.history(principal, category, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record GenerateRequest(
      LocalDate periodFrom,
      LocalDate periodTo,
      Map<String, Object> filters,
      String format,
      Boolean async) {
    public GenerateRequest {
      filters =
          filters == null
              ? null
              : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(filters));
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ScheduleRequest(
      Boolean enabled, String cadence, List<String> emailRecipients, String format) {
    public ScheduleRequest {
      emailRecipients = emailRecipients == null ? null : List.copyOf(emailRecipients);
    }
  }
}

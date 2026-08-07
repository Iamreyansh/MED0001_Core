package com.nammamedmate.pharmacy.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pharmacy.application.AdminBulkActionService;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore.JobRow;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin bulk jobs")
public class AdminBulkJobController {

  private final AdminBulkActionService bulkService;
  private final BulkActionJobStore jobs;

  public AdminBulkJobController(AdminBulkActionService bulkService, BulkActionJobStore jobs) {
    this.bulkService = bulkService;
    this.jobs = jobs;
  }

  @PostMapping("/pharmacies/bulk-action")
  @RequiresPermission("pharmacies:update")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Admin: bulk action on pharmacies")
  public ApiResponse<Map<String, Object>> bulkAction(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) BulkActionRequest body) {
    BulkActionRequest req = body == null ? new BulkActionRequest(null, null, null) : body;
    return ApiResponse.ok(
        bulkService.submitBulkAction(principal, req.pharmacyIds(), req.action(), req.payload()));
  }

  @GetMapping("/bulk-jobs/{job_id}")
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: poll bulk action job status")
  public ApiResponse<Map<String, Object>> getJob(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("job_id") UUID jobId) {
    return ApiResponse.ok(bulkService.getJobStatus(principal, jobId));
  }

  @GetMapping(value = "/bulk-jobs/{job_id}/export.csv", produces = "text/csv")
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: download bulk export CSV")
  public ResponseEntity<String> downloadExport(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("job_id") UUID jobId) {
    bulkService.getJobStatus(principal, jobId);
    JobRow job = jobs.findById(jobId).orElseThrow();
    String content =
        job.resultPayload() == null
            ? ""
            : String.valueOf(job.resultPayload().getOrDefault("export_content", ""));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"pharmacies-export.csv\"")
        .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
        .body(content);
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record BulkActionRequest(
      List<UUID> pharmacyIds, String action, Map<String, Object> payload) {
    public BulkActionRequest {
      if (pharmacyIds != null) {
        pharmacyIds = List.copyOf(pharmacyIds);
      }
      if (payload != null) {
        payload = Map.copyOf(payload);
      }
    }
  }
}

package com.nammamedmate.prescription.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.prescription.application.ScheduleDrugRegisterService;
import com.nammamedmate.prescription.application.ScheduleDrugRegisterService.ListResult;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/compliance/drug-register")
@Tag(name = "Admin Schedule H1/X drug register")
public class AdminDrugRegisterController {

  private final ScheduleDrugRegisterService service;

  public AdminDrugRegisterController(ScheduleDrugRegisterService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "View statutory Schedule H1/X drug register")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "schedule") String schedule,
      @RequestParam(name = "pharmacy_id", required = false) UUID pharmacyId,
      @RequestParam(name = "drug_name", required = false) String drugName,
      @RequestParam(name = "from_date", required = false) String fromDate,
      @RequestParam(name = "to_date", required = false) String toDate,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "export", required = false) Boolean export) {
    ListResult result =
        service.listAdmin(
            principal, schedule, pharmacyId, drugName, fromDate, toDate, page, limit, export);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/retention-rules")
  @Operation(summary = "Statutory retention rules for Schedule H1/X registers")
  public ApiResponse<Map<String, Object>> retentionRules(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.retentionRules(principal));
  }

  @PostMapping("/export")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Start async regulatory CSV export job")
  public ApiResponse<Map<String, Object>> export(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) ExportRequest body) {
    UUID pharmacyId = body == null ? null : body.pharmacyId();
    String schedule = body == null ? null : body.schedule();
    String from = body == null ? null : body.fromDate();
    String to = body == null ? null : body.toDate();
    return ApiResponse.ok(service.createExportJob(principal, pharmacyId, schedule, from, to));
  }

  @GetMapping("/export/{jobId}")
  @Operation(summary = "Poll drug register export job status")
  public ApiResponse<Map<String, Object>> pollExport(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID jobId) {
    return ApiResponse.ok(service.pollExportJob(principal, jobId));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ExportRequest(UUID pharmacyId, String schedule, String fromDate, String toDate) {}
}

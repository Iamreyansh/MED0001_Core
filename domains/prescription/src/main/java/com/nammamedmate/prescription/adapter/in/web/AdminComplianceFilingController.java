package com.nammamedmate.prescription.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.prescription.application.ComplianceFilingService;
import com.nammamedmate.prescription.application.ComplianceFilingService.ActivityResult;
import com.nammamedmate.prescription.application.ComplianceFilingService.ListResult;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
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
@RequestMapping("/api/v1/admin/compliance")
@Tag(name = "Admin compliance filings")
public class AdminComplianceFilingController {

  private final ComplianceFilingService service;

  public AdminComplianceFilingController(ComplianceFilingService service) {
    this.service = service;
  }

  @GetMapping("/filings")
  @Operation(summary = "List regulatory filing calendar")
  public ApiResponse<Map<String, Object>> listFilings(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "filing_type", required = false) String filingType,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "year", required = false) Integer year,
      @RequestParam(name = "include_archived", required = false) Boolean includeArchived,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "limit", required = false) Integer limit) {
    ListResult result =
        service.listFilings(principal, filingType, status, year, includeArchived, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/filings/{filingId}/generate")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Start async filing report generation")
  public ApiResponse<Map<String, Object>> generate(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID filingId,
      @RequestBody(required = false) GenerateRequest body) {
    String period = body == null ? null : body.period();
    String format = body == null ? null : body.format();
    return ApiResponse.ok(service.startGenerate(principal, filingId, period, format));
  }

  @GetMapping("/filings/{filingId}/generate/{jobId}")
  @Operation(summary = "Poll filing report generation job")
  public ApiResponse<Map<String, Object>> pollGenerate(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID filingId,
      @PathVariable UUID jobId) {
    return ApiResponse.ok(service.pollGenerate(principal, filingId, jobId));
  }

  @PostMapping("/filings/{filingId}/mark-filed")
  @Operation(summary = "Mark filing as submitted with reference number")
  public ApiResponse<Map<String, Object>> markFiled(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID filingId,
      @RequestBody(required = false) MarkFiledRequest body) {
    UUID filedBy = body == null ? null : body.filedBy();
    Instant filedAt = body == null ? null : body.filedAt();
    String ref = body == null ? null : body.referenceNumber();
    return ApiResponse.ok(service.markFiled(principal, filingId, filedBy, filedAt, ref));
  }

  @GetMapping("/activity-log")
  @Operation(summary = "Append-only compliance activity log")
  public ApiResponse<List<Map<String, Object>>> activityLog(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "action", required = false) String action,
      @RequestParam(name = "actor_id", required = false) UUID actorId,
      @RequestParam(name = "from_date", required = false) String fromDate,
      @RequestParam(name = "to_date", required = false) String toDate,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "limit", required = false) Integer limit) {
    ActivityResult result =
        service.listActivity(principal, action, actorId, fromDate, toDate, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/drug-recalls")
  @ResponseStatus(HttpStatus.OK)
  @Operation(summary = "Initiate platform-wide drug batch recall")
  public ApiResponse<Map<String, Object>> drugRecall(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) DrugRecallRequest body) {
    String drug = body == null ? null : body.drugName();
    String batch = body == null ? null : body.batchNo();
    String reason = body == null ? null : body.reason();
    return ApiResponse.ok(service.initiateDrugRecall(principal, drug, batch, reason));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record GenerateRequest(String period, String format) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record MarkFiledRequest(UUID filedBy, Instant filedAt, String referenceNumber) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DrugRecallRequest(String drugName, String batchNo, String reason) {}
}

package com.nammamedmate.prescription.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.prescription.application.RxComplianceAuditService;
import com.nammamedmate.prescription.application.RxComplianceAuditService.ListResult;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/prescriptions")
@Tag(name = "Admin Rx compliance audit")
public class AdminPrescriptionController {

  private final RxComplianceAuditService service;

  public AdminPrescriptionController(RxComplianceAuditService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List Rx compliance audit queue (or CSV export)")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "schedule", required = false) String schedule,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "source", required = false) String source,
      @RequestParam(name = "from_date", required = false) String fromDate,
      @RequestParam(name = "to_date", required = false) String toDate,
      @RequestParam(name = "search", required = false) String search,
      @RequestParam(name = "pharmacy_id", required = false) UUID pharmacyId,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "export", required = false) Boolean export) {
    ListResult result =
        service.list(
            principal,
            schedule,
            status,
            source,
            fromDate,
            toDate,
            search,
            pharmacyId,
            page,
            limit,
            export);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/statistics")
  @Operation(summary = "Compliance statistics by schedule")
  public ApiResponse<Map<String, Object>> statistics(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "from_date", required = false) String fromDate,
      @RequestParam(name = "to_date", required = false) String toDate) {
    return ApiResponse.ok(service.statistics(principal, fromDate, toDate));
  }

  @GetMapping("/{rxId}")
  @Operation(summary = "Get Rx audit detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID rxId) {
    return ApiResponse.ok(service.get(principal, rxId));
  }

  @PostMapping("/{rxId}/verify")
  @Operation(summary = "Verify prescription audit")
  public ApiResponse<Map<String, Object>> verify(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID rxId,
      @RequestBody(required = false) VerifyRequest body) {
    Boolean verified = body == null ? null : body.verified();
    String flagReason = body == null ? null : body.flagReason();
    String notes = body == null ? null : body.notes();
    return ApiResponse.ok(service.verify(principal, rxId, verified, flagReason, notes));
  }

  @PostMapping("/{rxId}/flag")
  @Operation(summary = "Flag prescription for investigation")
  public ApiResponse<Map<String, Object>> flag(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID rxId,
      @RequestBody(required = false) FlagRequest body) {
    String reason = body == null ? null : body.reason();
    String severity = body == null ? null : body.severity();
    return ApiResponse.ok(service.flag(principal, rxId, reason, severity));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record VerifyRequest(Boolean verified, String flagReason, String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record FlagRequest(String reason, String severity) {}
}

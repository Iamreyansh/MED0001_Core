package com.nammamedmate.pharmacy.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.pharmacy.application.AutoKycService;
import com.nammamedmate.pharmacy.application.PharmacyKycService;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@RequestMapping("/api/v1/admin/pharmacies")
@Tag(name = "Admin pharmacy KYC")
public class AdminPharmacyKycController {

  private final PharmacyKycService service;
  private final AutoKycService autoKyc;

  public AdminPharmacyKycController(PharmacyKycService service, AutoKycService autoKyc) {
    this.service = service;
    this.autoKyc = autoKyc;
  }

  @GetMapping("/{id}/kyc")
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: view all KYC documents for a pharmacy")
  public ApiResponse<Map<String, Object>> getKyc(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID id) {
    return ApiResponse.ok(service.adminGetKyc(principal, id));
  }

  @PostMapping("/{id}/kyc/documents/{docId}/verify")
  @RequiresPermission("pharmacies:update")
  @Operation(summary = "Admin: verify or reject a KYC document")
  public ApiResponse<Map<String, Object>> verifyDocument(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @PathVariable UUID docId,
      @RequestBody(required = false) VerifyRequest body) {
    if (body == null || body.verified() == null) {
      throw new AppException("VALIDATION_ERROR", "verified is required", 400);
    }
    return ApiResponse.ok(
        service.adminVerifyDocument(principal, id, docId, body.verified(), body.rejectionReason()));
  }

  @PostMapping("/{id}/kyc/auto-verify")
  @RequiresPermission("pharmacies:update")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Admin: trigger auto-KYC verification")
  public ApiResponse<Map<String, Object>> triggerAutoVerify(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @RequestBody(required = false) AutoVerifyRequest body) {
    List<String> checks = body == null ? null : body.checks();
    return ApiResponse.ok(autoKyc.adminTriggerAutoVerify(principal, id, checks));
  }

  @GetMapping("/{id}/kyc/auto-verify-result")
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: get auto-KYC verification results")
  public ApiResponse<Map<String, Object>> getAutoVerifyResult(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @RequestParam(name = "job_id", required = false) UUID jobId) {
    return ApiResponse.ok(autoKyc.adminGetAutoVerifyResult(principal, id, jobId));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record VerifyRequest(Boolean verified, String rejectionReason) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AutoVerifyRequest(List<String> checks) {
    public AutoVerifyRequest {
      checks = checks == null ? null : List.copyOf(checks);
    }
  }
}

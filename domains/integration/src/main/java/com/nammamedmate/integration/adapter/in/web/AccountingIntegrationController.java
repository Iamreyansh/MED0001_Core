package com.nammamedmate.integration.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.integration.application.AccountingService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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

/**
 * Accounting integration endpoints. {@code /api/v1/integrations/**} is permitAll in SecurityConfig
 * — this controller enforces JWT via {@link AuthenticationPrincipal} (null → 401).
 */
@RestController
@RequestMapping("/api/v1/integrations/accounting")
@Tag(name = "Accounting Integration")
public class AccountingIntegrationController {

  private final AccountingService service;

  public AccountingIntegrationController(AccountingService service) {
    this.service = service;
  }

  @PostMapping("/sync")
  @Operation(summary = "Trigger accounting sync (async job)")
  public ResponseEntity<ApiResponse<Map<String, Object>>> sync(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) SyncRequest body) {
    requireAuth(principal);
    SyncRequest req = body == null ? new SyncRequest(null, null, null, null, null) : body;
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            ApiResponse.ok(
                service.triggerSync(
                    principal,
                    req.pharmacyId(),
                    req.accountingSystem(),
                    req.syncType(),
                    req.periodFrom(),
                    req.periodTo())));
  }

  @GetMapping("/sync-status/{jobId}")
  @Operation(summary = "Poll accounting sync job status")
  public ApiResponse<Map<String, Object>> syncStatus(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("jobId") UUID jobId) {
    requireAuth(principal);
    return ApiResponse.ok(service.syncStatus(principal, jobId));
  }

  @GetMapping("/config")
  @Operation(summary = "Get accounting integration config")
  public ApiResponse<Map<String, Object>> getConfig(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    requireAuth(principal);
    return ApiResponse.ok(service.getConfig(principal));
  }

  @PatchMapping("/config")
  @Operation(summary = "Update accounting integration config")
  public ApiResponse<Map<String, Object>> patchConfig(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) PatchConfigRequest body) {
    requireAuth(principal);
    PatchConfigRequest req = body == null ? new PatchConfigRequest(null, null, null) : body;
    return ApiResponse.ok(
        service.patchConfig(
            principal, req.accountingSystem(), req.autoSyncEnabled(), req.syncFrequency()));
  }

  @GetMapping("/export-tally-xml")
  @Operation(summary = "Export Tally-compatible XML for manual import")
  public ApiResponse<Map<String, Object>> exportTallyXml(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam("pharmacy_id") UUID pharmacyId,
      @RequestParam("sync_type") String syncType,
      @RequestParam("period_from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate periodFrom,
      @RequestParam("period_to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate periodTo) {
    requireAuth(principal);
    return ApiResponse.ok(
        service.exportTallyXml(principal, pharmacyId, syncType, periodFrom, periodTo));
  }

  private static void requireAuth(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SyncRequest(
      UUID pharmacyId,
      String accountingSystem,
      String syncType,
      LocalDate periodFrom,
      LocalDate periodTo) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PatchConfigRequest(
      String accountingSystem, Boolean autoSyncEnabled, String syncFrequency) {}
}

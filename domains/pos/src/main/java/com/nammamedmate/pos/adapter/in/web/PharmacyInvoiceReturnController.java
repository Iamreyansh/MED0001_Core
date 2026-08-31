package com.nammamedmate.pos.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pos.application.PosInsuranceClaimService;
import com.nammamedmate.pos.application.PosReturnService;
import com.nammamedmate.pos.application.PosReturnService.ReturnLine;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/invoices")
@Tag(name = "Pharmacy invoice returns and TPA")
public class PharmacyInvoiceReturnController {

  private final PosReturnService returns;
  private final PosInsuranceClaimService claims;

  public PharmacyInvoiceReturnController(
      PosReturnService returns, PosInsuranceClaimService claims) {
    this.returns = returns;
    this.claims = claims;
  }

  @PostMapping("/{invoiceId}/return")
  @Operation(summary = "Issue a credit note and restock returned lines")
  public ResponseEntity<ApiResponse<Map<String, Object>>> createReturn(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID invoiceId,
      @RequestBody(required = false) ReturnRequest body) {
    ReturnRequest req = body == null ? new ReturnRequest(null, null) : body;
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ApiResponse.ok(returns.createReturn(principal, invoiceId, req.reason(), req.items())));
  }

  @PostMapping("/{invoiceId}/insurance-claim")
  @Operation(summary = "Submit a TPA claim for an INSURANCE_TPA invoice")
  public ResponseEntity<ApiResponse<Map<String, Object>>> submitClaim(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID invoiceId,
      @RequestBody(required = false) ClaimRequest body) {
    ClaimRequest req = body == null ? new ClaimRequest(null, null, null) : body;
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ApiResponse.ok(
                claims.submit(
                    principal, invoiceId, req.tpaName(), req.policyNumber(), req.notes())));
  }

  @GetMapping("/{invoiceId}/insurance-claim")
  @Operation(summary = "Get the TPA claim for an invoice")
  public ApiResponse<Map<String, Object>> getClaim(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID invoiceId) {
    return ApiResponse.ok(claims.get(principal, invoiceId));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReturnRequest(String reason, List<ReturnLine> items) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ClaimRequest(String tpaName, String policyNumber, String notes) {}
}

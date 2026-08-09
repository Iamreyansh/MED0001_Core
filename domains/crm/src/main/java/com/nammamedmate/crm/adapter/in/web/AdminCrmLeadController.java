package com.nammamedmate.crm.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.crm.application.LeadPipelineService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
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
@RequestMapping("/api/v1/admin/crm/leads")
@Tag(name = "Admin CRM lead pipeline")
public class AdminCrmLeadController {

  private final LeadPipelineService leads;

  public AdminCrmLeadController(LeadPipelineService leads) {
    this.leads = leads;
  }

  @GetMapping
  @RequiresPermission("crm:read")
  @Operation(summary = "Admin: list leads with pipeline KPI chips")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String stage,
      @RequestParam(name = "rep_id", required = false) UUID repId,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    LeadPipelineService.PagedResult result =
        leads.list(principal, stage, repId, source, q, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping
  @RequiresPermission("crm:write")
  @Operation(summary = "Admin: create lead at NEW stage")
  public ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody CreateLeadRequest body) {
    CreateLeadRequest req =
        body == null
            ? new CreateLeadRequest(null, null, null, null, null, null, null, null, null)
            : body;
    Map<String, Object> data =
        leads.create(
            principal,
            req.pharmacyName(),
            req.contactName(),
            req.phone(),
            req.email(),
            req.source(),
            req.targetPlan(),
            req.estimatedMrrRs(),
            req.assignedRepId(),
            req.pharmacyId());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @GetMapping("/{id}")
  @RequiresPermission("crm:read")
  @Operation(summary = "Admin: lead detail with activity timeline")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(leads.get(principal, id));
  }

  @PatchMapping("/{id}")
  @RequiresPermission("crm:write")
  @Operation(summary = "Admin: update lead assignment, MRR, notes, win probability")
  public ApiResponse<Map<String, Object>> patch(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) UpdateLeadRequest body) {
    return ApiResponse.ok(
        leads.update(
            principal,
            id,
            body == null ? null : body.assignedRepId(),
            body == null ? null : body.estimatedMrrRs(),
            body == null ? null : body.winProbability(),
            body == null ? null : body.notes(),
            body != null && body.assignedRepId() != null,
            body != null && body.estimatedMrrRs() != null,
            body != null && body.winProbability() != null,
            body != null && body.notes() != null));
  }

  @PostMapping("/{id}/advance")
  @RequiresPermission("crm:write")
  @Operation(summary = "Admin: advance lead one stage forward")
  public ApiResponse<Map<String, Object>> advance(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) NotesRequest body) {
    return ApiResponse.ok(leads.advance(principal, id, body == null ? null : body.notes()));
  }

  @PostMapping("/{id}/mark-won")
  @RequiresPermission("crm:write")
  @Operation(summary = "Admin: mark lead won and create/upgrade subscription")
  public ApiResponse<Map<String, Object>> markWon(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody MarkWonRequest body) {
    return ApiResponse.ok(
        leads.markWon(
            principal,
            id,
            body == null ? null : body.planId(),
            body == null ? null : body.billingCycle()));
  }

  @PostMapping("/{id}/mark-lost")
  @RequiresPermission("crm:write")
  @Operation(summary = "Admin: mark lead lost with reason")
  public ApiResponse<Map<String, Object>> markLost(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody MarkLostRequest body) {
    return ApiResponse.ok(
        leads.markLost(
            principal,
            id,
            body == null ? null : body.lostReason(),
            body == null ? null : body.notes()));
  }

  @PostMapping("/{id}/reopen")
  @RequiresPermission("crm:write")
  @Operation(summary = "Admin: reopen LOST lead to CONTACTED")
  public ApiResponse<Map<String, Object>> reopen(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(leads.reopen(principal, id));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateLeadRequest(
      String pharmacyName,
      String contactName,
      String phone,
      String email,
      String source,
      String targetPlan,
      BigDecimal estimatedMrrRs,
      UUID assignedRepId,
      UUID pharmacyId) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UpdateLeadRequest(
      UUID assignedRepId, BigDecimal estimatedMrrRs, Integer winProbability, String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record NotesRequest(String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record MarkWonRequest(UUID planId, String billingCycle) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record MarkLostRequest(String lostReason, String notes) {}
}

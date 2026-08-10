package com.nammamedmate.support.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.support.application.SlaService;
import com.nammamedmate.support.application.port.out.EscalationMatrixStore.RulePatch;
import com.nammamedmate.support.domain.SlaLevel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/support")
@Tag(name = "Admin support SLA")
public class AdminSupportSlaController {

  private final SlaService sla;

  public AdminSupportSlaController(SlaService sla) {
    this.sla = sla;
  }

  @GetMapping("/sla-policies")
  @Operation(summary = "List SLA policies")
  public ResponseEntity<ApiResponse<Map<String, Object>>> listPolicies(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ResponseEntity.ok(ApiResponse.ok(sla.listPolicies(principal)));
  }

  @PatchMapping("/sla-policies/{id}")
  @Operation(summary = "Update SLA policy (admin_super only)")
  public ResponseEntity<ApiResponse<Map<String, Object>>> updatePolicy(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @RequestBody(required = false) UpdatePolicyRequest body) {
    UpdatePolicyRequest req = body == null ? new UpdatePolicyRequest(null, null) : body;
    return ResponseEntity.ok(
        ApiResponse.ok(
            sla.updatePolicy(
                principal, id, req.firstResponseSlaMinutes(), req.resolutionSlaMinutes())));
  }

  @GetMapping("/sla-breaches")
  @Operation(summary = "Live SLA breach list")
  public ResponseEntity<ApiResponse<Map<String, Object>>> listBreaches(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "breach_type", required = false) String breachType,
      @RequestParam(name = "sla_level", required = false) String slaLevel,
      @RequestParam(name = "assigned_agent_id", required = false) UUID assignedAgentId) {
    return ResponseEntity.ok(
        ApiResponse.ok(sla.listBreaches(principal, breachType, slaLevel, assignedAgentId)));
  }

  @GetMapping("/escalation-matrix")
  @Operation(summary = "Get escalation matrix")
  public ResponseEntity<ApiResponse<Map<String, Object>>> getMatrix(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ResponseEntity.ok(ApiResponse.ok(sla.getEscalationMatrix(principal)));
  }

  @PatchMapping("/escalation-matrix")
  @Operation(summary = "Update escalation matrix (admin_super only)")
  public ResponseEntity<ApiResponse<Map<String, Object>>> updateMatrix(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) UpdateMatrixRequest body) {
    List<RulePatch> patches = new ArrayList<>();
    if (body != null && body.escalationRules() != null) {
      for (MatrixRulePatch r : body.escalationRules()) {
        if (r == null || r.level() == null || r.level().isBlank()) {
          continue;
        }
        patches.add(
            new RulePatch(
                SlaLevel.valueOf(r.level().trim().toUpperCase(Locale.ROOT)),
                r.autoEscalateAfterMinutes(),
                r.notificationChannel()));
      }
    }
    return ResponseEntity.ok(ApiResponse.ok(sla.updateEscalationMatrix(principal, patches)));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UpdatePolicyRequest(
      Integer firstResponseSlaMinutes, Integer resolutionSlaMinutes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UpdateMatrixRequest(List<MatrixRulePatch> escalationRules) {
    public UpdateMatrixRequest {
      escalationRules =
          escalationRules == null
              ? null
              : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(escalationRules));
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record MatrixRulePatch(
      String level, Integer autoEscalateAfterMinutes, List<String> notificationChannel) {
    public MatrixRulePatch {
      notificationChannel = notificationChannel == null ? null : List.copyOf(notificationChannel);
    }
  }
}

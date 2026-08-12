package com.nammamedmate.observability_ops.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.observability_ops.application.IncidentService;
import com.nammamedmate.observability_ops.application.IncidentService.IncidentsPage;
import com.nammamedmate.observability_ops.application.MonitoringQueryService;
import com.nammamedmate.observability_ops.application.MonitoringQueryService.AlertsPage;
import com.nammamedmate.observability_ops.application.RemediationService;
import com.nammamedmate.observability_ops.application.RemediationService.ActionsPage;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/monitoring")
@Tag(name = "Admin realtime monitoring")
public class AdminMonitoringController {

  private final MonitoringQueryService monitoring;
  private final RemediationService remediation;
  private final IncidentService incidents;

  public AdminMonitoringController(
      MonitoringQueryService monitoring,
      RemediationService remediation,
      IncidentService incidents) {
    this.monitoring = monitoring;
    this.remediation = remediation;
    this.incidents = incidents;
  }

  @GetMapping("/realtime")
  @Operation(summary = "Live platform health overview")
  public ApiResponse<Map<String, Object>> realtime(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(monitoring.realtime(principal));
  }

  @GetMapping("/alerts")
  @Operation(summary = "List monitoring alerts")
  public ApiResponse<Map<String, Object>> alerts(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) Integer page) {
    AlertsPage result = monitoring.alerts(principal, status, severity, page);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/alerts/{id}/acknowledge")
  @Operation(summary = "Acknowledge a monitoring alert")
  public ApiResponse<Map<String, Object>> acknowledge(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) AcknowledgeRequest body) {
    String notes = body == null ? null : body.notes();
    return ApiResponse.ok(monitoring.acknowledge(principal, id, notes));
  }

  @GetMapping("/metrics")
  @Operation(summary = "Metric time-series for charts")
  public ApiResponse<Map<String, Object>> metrics(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam String metric_name,
      @RequestParam(required = false) Integer period_minutes) {
    return ApiResponse.ok(monitoring.metrics(principal, metric_name, period_minutes));
  }

  @GetMapping("/slo")
  @Operation(summary = "SLO dashboard with error budgets")
  public ApiResponse<Map<String, Object>> slo(@AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(monitoring.slo(principal));
  }

  @GetMapping("/slo/history")
  @Operation(summary = "SLO compliance history")
  public ApiResponse<Map<String, Object>> sloHistory(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String slo_name,
      @RequestParam(required = false) String period_from,
      @RequestParam(required = false) String period_to) {
    return ApiResponse.ok(incidents.sloHistory(principal, slo_name, period_from, period_to));
  }

  @GetMapping("/incidents")
  @Operation(summary = "List monitoring incidents")
  public ApiResponse<Map<String, Object>> listIncidents(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to,
      @RequestParam(required = false) Integer page) {
    IncidentsPage result = incidents.list(principal, status, severity, date_from, date_to, page);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/incidents")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Declare a monitoring incident")
  public ApiResponse<Map<String, Object>> createIncident(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) CreateIncidentRequest body) {
    return ApiResponse.ok(
        incidents.declare(
            principal,
            body == null ? null : body.title(),
            body == null ? null : body.severity(),
            body == null ? null : body.description(),
            body == null ? null : body.affectedServices(),
            body == null ? null : body.impactedMetrics()));
  }

  @PatchMapping("/incidents/{id}")
  @Operation(summary = "Update incident status")
  public ApiResponse<Map<String, Object>> patchIncident(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) PatchIncidentRequest body) {
    return ApiResponse.ok(
        incidents.patchStatus(
            principal,
            id,
            body == null ? null : body.status(),
            body == null ? null : body.updateMessage()));
  }

  @PostMapping("/incidents/{id}/resolve")
  @Operation(summary = "Resolve an incident")
  public ApiResponse<Map<String, Object>> resolveIncident(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) ResolveIncidentRequest body) {
    return ApiResponse.ok(
        incidents.resolve(
            principal,
            id,
            body == null ? null : body.rootCause(),
            body == null ? null : body.fixApplied(),
            body == null ? null : body.preventionSteps()));
  }

  @PutMapping("/incidents/{id}/postmortem")
  @Operation(summary = "Mark postmortem filed")
  public ApiResponse<Map<String, Object>> filePostmortem(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(incidents.filePostmortem(principal, id));
  }

  @GetMapping("/remediation-actions")
  @Operation(summary = "List remediation actions")
  public ApiResponse<Map<String, Object>> remediationActions(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String action_type,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String date_from,
      @RequestParam(required = false) String date_to,
      @RequestParam(required = false) Integer page) {
    ActionsPage result =
        remediation.listActions(principal, action_type, status, date_from, date_to, page);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/remediation-actions")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Manually trigger a remediation action")
  public ApiResponse<Map<String, Object>> triggerRemediation(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody TriggerRemediationRequest body) {
    return ApiResponse.ok(
        remediation.triggerManual(
            principal,
            body == null ? null : body.actionType(),
            body == null ? null : body.targetEntityType(),
            body == null ? null : body.targetEntityId(),
            body == null ? null : body.reason()));
  }

  @GetMapping("/remediation-playbooks")
  @Operation(summary = "List remediation playbooks")
  public ApiResponse<Map<String, Object>> remediationPlaybooks(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(remediation.listPlaybooks(principal));
  }

  @PatchMapping("/remediation-playbooks/{id}")
  @Operation(summary = "Update remediation playbook")
  public ApiResponse<Map<String, Object>> patchPlaybook(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) PatchPlaybookRequest body) {
    return ApiResponse.ok(
        remediation.patchPlaybook(
            principal,
            id,
            body == null ? null : body.isEnabled(),
            body == null ? null : body.threshold()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AcknowledgeRequest(String notes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record TriggerRemediationRequest(
      String actionType, String targetEntityType, UUID targetEntityId, String reason) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PatchPlaybookRequest(Boolean isEnabled, Map<String, Object> threshold) {
    public PatchPlaybookRequest {
      threshold = threshold == null ? null : Map.copyOf(threshold);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateIncidentRequest(
      String title,
      String severity,
      String description,
      List<String> affectedServices,
      Map<String, Object> impactedMetrics) {
    public CreateIncidentRequest {
      affectedServices = affectedServices == null ? null : List.copyOf(affectedServices);
      impactedMetrics = impactedMetrics == null ? null : Map.copyOf(impactedMetrics);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PatchIncidentRequest(String status, String updateMessage) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ResolveIncidentRequest(
      String rootCause, String fixApplied, String preventionSteps) {}
}

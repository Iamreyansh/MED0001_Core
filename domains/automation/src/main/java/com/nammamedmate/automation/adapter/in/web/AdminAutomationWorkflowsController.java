package com.nammamedmate.automation.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.automation.application.WorkflowManagementService;
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.automation.domain.StepType;
import com.nammamedmate.automation.domain.WorkflowStep;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
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
@RequestMapping("/api/v1/admin/automation/workflows")
@Tag(name = "Admin automation workflows")
public class AdminAutomationWorkflowsController {

  private final WorkflowManagementService workflows;

  public AdminAutomationWorkflowsController(WorkflowManagementService workflows) {
    this.workflows = workflows;
  }

  @GetMapping
  @Operation(summary = "List automation workflows")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(workflows.list(principal));
  }

  @PostMapping
  @Operation(summary = "Create workflow (always INACTIVE)")
  public ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) CreateWorkflowRequest body) {
    CreateWorkflowRequest req =
        body == null ? new CreateWorkflowRequest(null, null, null, null) : body;
    Map<String, Object> data =
        workflows.create(
            principal, req.name(), req.description(), req.triggerId(), mapSteps(req.steps()));
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get workflow detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(workflows.get(principal, id));
  }

  @PatchMapping("/{id}")
  @Operation(summary = "Update workflow (pauses active executions, versions++)")
  public ApiResponse<Map<String, Object>> patch(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) PatchWorkflowRequest body) {
    PatchWorkflowRequest req =
        body == null ? new PatchWorkflowRequest(null, null, null, null) : body;
    return ApiResponse.ok(
        workflows.patch(
            principal,
            id,
            req.name(),
            req.description(),
            req.triggerId(),
            req.steps() == null ? null : mapSteps(req.steps())));
  }

  @PostMapping("/{id}/toggle")
  @Operation(summary = "Enable or disable workflow (admin_super only)")
  public ApiResponse<Map<String, Object>> toggle(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) ToggleRequest body) {
    ToggleRequest req = body == null ? new ToggleRequest(null) : body;
    return ApiResponse.ok(workflows.toggle(principal, id, req.status()));
  }

  @GetMapping("/{id}/executions")
  @Operation(summary = "List workflow executions")
  public ApiResponse<Map<String, Object>> listExecutions(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page) {
    WorkflowManagementService.PagedResult result =
        workflows.listExecutions(principal, id, status, page);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/{id}/executions/{executionId}/cancel")
  @Operation(summary = "Cancel a workflow execution")
  public ApiResponse<Map<String, Object>> cancel(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @PathVariable("executionId") UUID executionId) {
    return ApiResponse.ok(workflows.cancel(principal, id, executionId));
  }

  private static List<WorkflowStep> mapSteps(List<StepDto> dtos) {
    if (dtos == null) {
      return List.of();
    }
    List<WorkflowStep> out = new ArrayList<>();
    for (StepDto d : dtos) {
      ConditionSpec condition = null;
      if (d.condition() != null) {
        condition =
            new ConditionSpec(
                d.condition().field(), d.condition().operator(), d.condition().value());
      }
      StepType type = null;
      try {
        type = StepType.parse(d.type());
      } catch (RuntimeException ignored) {
        // validator rejects null type
      }
      out.add(
          new WorkflowStep(
              d.stepId(),
              type,
              d.actionId(),
              d.params() == null ? Map.of() : d.params(),
              d.waitDurationHours(),
              condition,
              d.nextStepIdOnTrue(),
              d.nextStepIdOnFalse()));
    }
    return out;
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateWorkflowRequest(
      String name, String description, String triggerId, List<StepDto> steps) {
    public CreateWorkflowRequest {
      steps = steps == null ? null : List.copyOf(steps);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PatchWorkflowRequest(
      String name, String description, String triggerId, List<StepDto> steps) {
    public PatchWorkflowRequest {
      steps = steps == null ? null : List.copyOf(steps);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ToggleRequest(String status) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record StepDto(
      String stepId,
      String type,
      String actionId,
      Map<String, Object> params,
      Integer waitDurationHours,
      ConditionDto condition,
      String nextStepIdOnTrue,
      String nextStepIdOnFalse) {
    public StepDto {
      params = params == null ? null : Map.copyOf(params);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ConditionDto(String field, String operator, Object value) {}
}

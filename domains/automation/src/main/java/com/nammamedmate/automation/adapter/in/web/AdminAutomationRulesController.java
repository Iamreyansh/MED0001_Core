package com.nammamedmate.automation.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.automation.application.RuleManagementService;
import com.nammamedmate.automation.application.RuleSimulationService;
import com.nammamedmate.automation.domain.ActionSpec;
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.automation.domain.Guardrails;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/automation/rules")
@Tag(name = "Admin automation rules")
public class AdminAutomationRulesController {

  private final RuleManagementService rules;
  private final RuleSimulationService simulations;

  public AdminAutomationRulesController(
      RuleManagementService rules, RuleSimulationService simulations) {
    this.rules = rules;
    this.simulations = simulations;
  }

  @GetMapping
  @Operation(summary = "List automation rules")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(name = "trigger_category", required = false) String triggerCategory,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    RuleManagementService.PagedResult result =
        rules.list(principal, status, triggerCategory, search, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping
  @Operation(summary = "Create automation rule (always INACTIVE)")
  public ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) CreateRuleRequest body) {
    CreateRuleRequest req =
        body == null ? new CreateRuleRequest(null, null, null, null, null, null, null, null) : body;
    Map<String, Object> data =
        rules.create(
            principal,
            req.name(),
            req.description(),
            req.triggerId(),
            req.triggerParams(),
            mapConditions(req.conditions()),
            mapActions(req.actions()),
            mapGuardrails(req.guardrails()),
            req.dedupWindowSeconds());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get automation rule detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(rules.get(principal, id));
  }

  @PatchMapping("/{id}")
  @Operation(summary = "Update rule configuration (ACTIVE → INACTIVE)")
  public ApiResponse<Map<String, Object>> patch(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) PatchRuleRequest body) {
    PatchRuleRequest req =
        body == null ? new PatchRuleRequest(null, null, null, null, null, null, null, null) : body;
    return ApiResponse.ok(
        rules.patch(
            principal,
            id,
            req.name(),
            req.description(),
            req.triggerId(),
            req.triggerParams(),
            req.conditions() == null ? null : mapConditions(req.conditions()),
            req.actions() == null ? null : mapActions(req.actions()),
            req.guardrails() == null ? null : mapGuardrails(req.guardrails()),
            req.dedupWindowSeconds()));
  }

  @PatchMapping("/{id}/status")
  @Operation(summary = "Set rule status")
  public ApiResponse<Map<String, Object>> setStatus(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) StatusRequest body) {
    StatusRequest req = body == null ? new StatusRequest(null) : body;
    return ApiResponse.ok(rules.setStatus(principal, id, req.status()));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Delete automation rule")
  public ApiResponse<Map<String, Object>> delete(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestParam(required = false, defaultValue = "false") boolean force) {
    return ApiResponse.ok(rules.delete(principal, id, force));
  }

  @PostMapping("/{id}/duplicate")
  @Operation(summary = "Duplicate automation rule")
  public ResponseEntity<ApiResponse<Map<String, Object>>> duplicate(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(rules.duplicate(principal, id)));
  }

  @PostMapping("/{id}/simulate")
  @Operation(summary = "Start batch historical simulation (202)")
  public ResponseEntity<ApiResponse<Map<String, Object>>> simulate(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) SimulateRequest body) {
    SimulateRequest req = body == null ? new SimulateRequest(null, null) : body;
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            ApiResponse.ok(simulations.startBatch(principal, id, req.sampleSize(), req.dryRun())));
  }

  @GetMapping("/{id}/simulation-results/{simulationId}")
  @Operation(summary = "Get batch simulation results (RUNNING while in progress)")
  public ApiResponse<Map<String, Object>> simulationResults(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @PathVariable("simulationId") UUID simulationId) {
    return ApiResponse.ok(simulations.getResults(principal, id, simulationId));
  }

  @PostMapping("/{id}/simulation-preview")
  @Operation(summary = "Live preview rule against one entity")
  public ApiResponse<Map<String, Object>> simulationPreview(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) PreviewRequest body) {
    PreviewRequest req = body == null ? new PreviewRequest(null, null) : body;
    return ApiResponse.ok(simulations.preview(principal, id, req.entityType(), req.entityId()));
  }

  private static List<ConditionSpec> mapConditions(List<ConditionDto> dtos) {
    if (dtos == null) {
      return List.of();
    }
    List<ConditionSpec> out = new ArrayList<>();
    for (ConditionDto d : dtos) {
      out.add(new ConditionSpec(d.field(), d.operator(), d.value()));
    }
    return out;
  }

  private static List<ActionSpec> mapActions(List<ActionDto> dtos) {
    if (dtos == null) {
      return List.of();
    }
    List<ActionSpec> out = new ArrayList<>();
    for (ActionDto d : dtos) {
      out.add(
          new ActionSpec(
              d.actionId(),
              d.params() == null ? Map.of() : d.params(),
              Boolean.TRUE.equals(d.parallel())));
    }
    return out;
  }

  private static Guardrails mapGuardrails(GuardrailsDto dto) {
    if (dto == null) {
      return Guardrails.NONE;
    }
    Guardrails.RateLimit rl = null;
    RateLimitDto rate = dto.rateLimit();
    if (rate != null) {
      Integer max = rate.maxFires();
      Integer per = rate.perMinutes();
      if (max != null && per != null) {
        rl = new Guardrails.RateLimit(max, per);
      }
    }
    return new Guardrails(
        rl,
        dto.valueCap(),
        dto.requireApprovalAbove(),
        Boolean.TRUE.equals(dto.requireApproval()),
        dto.onRejectAction());
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateRuleRequest(
      String name,
      String description,
      String triggerId,
      Map<String, Object> triggerParams,
      List<ConditionDto> conditions,
      List<ActionDto> actions,
      GuardrailsDto guardrails,
      Integer dedupWindowSeconds) {
    public CreateRuleRequest {
      triggerParams = triggerParams == null ? null : Map.copyOf(triggerParams);
      conditions = conditions == null ? null : List.copyOf(conditions);
      actions = actions == null ? null : List.copyOf(actions);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PatchRuleRequest(
      String name,
      String description,
      String triggerId,
      Map<String, Object> triggerParams,
      List<ConditionDto> conditions,
      List<ActionDto> actions,
      GuardrailsDto guardrails,
      Integer dedupWindowSeconds) {
    public PatchRuleRequest {
      triggerParams = triggerParams == null ? null : Map.copyOf(triggerParams);
      conditions = conditions == null ? null : List.copyOf(conditions);
      actions = actions == null ? null : List.copyOf(actions);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record StatusRequest(String status) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SimulateRequest(Integer sampleSize, Boolean dryRun) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PreviewRequest(String entityType, UUID entityId) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ConditionDto(String field, String operator, Object value) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ActionDto(String actionId, Map<String, Object> params, Boolean parallel) {
    public ActionDto {
      params = params == null ? null : Map.copyOf(params);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record GuardrailsDto(
      RateLimitDto rateLimit,
      Long valueCap,
      Long requireApprovalAbove,
      Boolean requireApproval,
      String onRejectAction) {

    public GuardrailsDto(RateLimitDto rateLimit, Long valueCap, Long requireApprovalAbove) {
      this(rateLimit, valueCap, requireApprovalAbove, null, null);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RateLimitDto(Integer maxFires, Integer perMinutes) {}
}

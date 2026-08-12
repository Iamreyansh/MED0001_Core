package com.nammamedmate.automation.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.automation.application.InternalAutomationAuth;
import com.nammamedmate.automation.application.RulesEngineService;
import com.nammamedmate.automation.application.RulesEngineService.EvaluateCommand;
import com.nammamedmate.automation.application.RulesEngineService.EventPayload;
import com.nammamedmate.automation.application.WorkflowEngineService;
import com.nammamedmate.automation.domain.ActionSpec;
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.kernel.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/automation/rules")
@Tag(name = "Internal automation rules evaluate")
public class InternalRulesEvaluateController {

  private final RulesEngineService engine;
  private final WorkflowEngineService workflows;
  private final InternalAutomationAuth internalAuth;

  public InternalRulesEvaluateController(
      RulesEngineService engine,
      WorkflowEngineService workflows,
      InternalAutomationAuth internalAuth) {
    this.engine = engine;
    this.workflows = workflows;
    this.internalAuth = internalAuth;
  }

  @PostMapping("/evaluate")
  @Operation(summary = "Evaluate a rule against an event (S2S X-Internal-Token)")
  public ApiResponse<Map<String, Object>> evaluate(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody EvaluateRequest body) {
    internalAuth.require(internalToken);
    EvaluateRequest req =
        body == null ? new EvaluateRequest(null, null, null, null, null, null) : body;
    EventPayload event =
        req.event() == null
            ? new EventPayload(null, null, null, Map.of(), null)
            : new EventPayload(
                req.event().triggerId(),
                req.event().entityType(),
                req.event().entityId(),
                req.event().payload(),
                req.event().firedAt());
    List<ConditionSpec> conditions = mapConditions(req.conditions());
    List<ActionSpec> actions = mapActions(req.actions());
    Map<String, Object> result =
        engine.evaluate(
            new EvaluateCommand(
                req.ruleId(),
                event,
                Boolean.TRUE.equals(req.dryRun()),
                conditions,
                actions,
                req.dedupWindowSeconds()));
    if (!Boolean.TRUE.equals(req.dryRun())
        && event.triggerId() != null
        && event.entityId() != null) {
      Object name = event.payload() == null ? null : event.payload().get("entity_name");
      workflows.onTrigger(
          event.triggerId(),
          event.entityType(),
          event.entityId(),
          name == null ? null : String.valueOf(name),
          event.payload());
    }
    return ApiResponse.ok(result);
  }

  private static List<ConditionSpec> mapConditions(List<ConditionDto> dtos) {
    if (dtos == null) {
      return null;
    }
    List<ConditionSpec> out = new ArrayList<>();
    for (ConditionDto d : dtos) {
      out.add(new ConditionSpec(d.field(), d.operator(), d.value()));
    }
    return out;
  }

  private static List<ActionSpec> mapActions(List<ActionDto> dtos) {
    if (dtos == null) {
      return null;
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

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record EvaluateRequest(
      UUID ruleId,
      EventDto event,
      Boolean dryRun,
      List<ConditionDto> conditions,
      List<ActionDto> actions,
      Integer dedupWindowSeconds) {
    public EvaluateRequest {
      conditions = conditions == null ? null : List.copyOf(conditions);
      actions = actions == null ? null : List.copyOf(actions);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record EventDto(
      String triggerId,
      String entityType,
      UUID entityId,
      Map<String, Object> payload,
      Instant firedAt) {
    public EventDto {
      payload = payload == null ? null : Map.copyOf(payload);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ConditionDto(String field, String operator, Object value) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ActionDto(String actionId, Map<String, Object> params, Boolean parallel) {
    public ActionDto {
      params = params == null ? null : Map.copyOf(params);
    }
  }
}

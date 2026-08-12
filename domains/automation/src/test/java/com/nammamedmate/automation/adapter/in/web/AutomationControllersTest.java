package com.nammamedmate.automation.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.ActionCatalogService;
import com.nammamedmate.automation.application.InternalAutomationAuth;
import com.nammamedmate.automation.application.RulesEngineService;
import com.nammamedmate.automation.application.TriggerCatalogService;
import com.nammamedmate.automation.application.WorkflowEngineService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AutomationControllersTest {

  @Mock TriggerCatalogService triggers;
  @Mock ActionCatalogService actions;
  @Mock RulesEngineService engine;
  @Mock WorkflowEngineService workflows;
  @Mock InternalAutomationAuth auth;

  @InjectMocks AdminAutomationTriggersController triggersController;
  @InjectMocks AdminAutomationActionsController actionsController;

  @Test
  void catalogsDelegate() {
    when(triggers.list("DISPATCH")).thenReturn(Map.of("total_triggers", 3));
    when(actions.list()).thenReturn(Map.of("total_actions", 16));
    assertThat(triggersController.list("DISPATCH").data()).containsEntry("total_triggers", 3);
    assertThat(actionsController.list().data()).containsEntry("total_actions", 16);
  }

  @Test
  void evaluateMapsBodyAndAuth() {
    InternalRulesEvaluateController controller =
        new InternalRulesEvaluateController(engine, workflows, auth);
    when(engine.evaluate(any())).thenReturn(Map.of("conditions_met", true));
    UUID ruleId = UUID.randomUUID();
    UUID entityId = UUID.randomUUID();
    var body =
        new InternalRulesEvaluateController.EvaluateRequest(
            ruleId,
            new InternalRulesEvaluateController.EventDto(
                "order_unassigned",
                "ORDER",
                entityId,
                Map.of("coverage_status", "OK"),
                Instant.parse("2026-07-24T08:07:00Z")),
            true,
            List.of(
                new InternalRulesEvaluateController.ConditionDto(
                    "coverage_status", "not_eq", "NO_RIDERS")),
            List.of(
                new InternalRulesEvaluateController.ActionDto(
                    "auto_assign_rider", Map.of("order_id", entityId.toString()), false)),
            300);

    assertThat(controller.evaluate("token", body).data()).containsEntry("conditions_met", true);
    verify(auth).require("token");
    ArgumentCaptor<RulesEngineService.EvaluateCommand> cap =
        ArgumentCaptor.forClass(RulesEngineService.EvaluateCommand.class);
    verify(engine).evaluate(cap.capture());
    assertThat(cap.getValue().dryRun()).isTrue();
    assertThat(cap.getValue().conditions()).hasSize(1);
    assertThat(cap.getValue().actions()).hasSize(1);

    assertThat(controller.evaluate("token", null).data()).containsEntry("conditions_met", true);
  }
}

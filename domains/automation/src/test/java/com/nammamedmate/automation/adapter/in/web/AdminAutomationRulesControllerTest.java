package com.nammamedmate.automation.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.RuleManagementService;
import com.nammamedmate.automation.application.RuleSimulationService;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AdminAutomationRulesControllerTest {

  @Mock RuleManagementService rules;
  @Mock RuleSimulationService simulations;
  @InjectMocks AdminAutomationRulesController controller;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @Test
  void delegatesCrud() {
    UUID id = UUID.randomUUID();
    when(rules.list(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new RuleManagementService.PagedResult(
                Map.of("rules", List.of()), PaginationMeta.of(1, 20, 0)));
    when(rules.create(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("status", "INACTIVE"));
    when(rules.get(any(), eq(id))).thenReturn(Map.of("rule_id", id));
    when(rules.patch(any(), eq(id), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("status", "INACTIVE"));
    when(rules.setStatus(any(), eq(id), any())).thenReturn(Map.of("new_status", "ACTIVE"));
    when(rules.delete(any(), eq(id), anyBoolean())).thenReturn(Map.of("deleted", true));
    when(rules.duplicate(any(), eq(id))).thenReturn(Map.of("name", "x (Copy)"));

    assertThat(controller.list(principal, null, null, null, 1, 20).success()).isTrue();
    assertThat(controller.create(principal, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(controller.get(principal, id).data()).containsEntry("rule_id", id);
    assertThat(controller.patch(principal, id, null).success()).isTrue();
    assertThat(
            controller
                .setStatus(
                    principal, id, new AdminAutomationRulesController.StatusRequest("ACTIVE"))
                .data())
        .containsEntry("new_status", "ACTIVE");
    assertThat(controller.delete(principal, id, true).data()).containsEntry("deleted", true);
    assertThat(controller.duplicate(principal, id).getStatusCode()).isEqualTo(HttpStatus.CREATED);

    when(simulations.startBatch(any(), eq(id), any(), any()))
        .thenReturn(Map.of("status", "RUNNING", "simulation_id", UUID.randomUUID()));
    when(simulations.getResults(any(), eq(id), any())).thenReturn(Map.of("status", "RUNNING"));
    when(simulations.preview(any(), eq(id), any(), any())).thenReturn(Map.of("would_fire", true));
    assertThat(controller.simulate(principal, id, null).getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    assertThat(controller.simulationResults(principal, id, UUID.randomUUID()).data().get("status"))
        .isEqualTo("RUNNING");
    assertThat(
            controller
                .simulationPreview(
                    principal,
                    id,
                    new AdminAutomationRulesController.PreviewRequest("ORDER", UUID.randomUUID()))
                .data()
                .get("would_fire"))
        .isEqualTo(true);

    var createBody =
        new AdminAutomationRulesController.CreateRuleRequest(
            "n",
            "d",
            "order_unassigned",
            Map.of(),
            List.of(new AdminAutomationRulesController.ConditionDto("a", "eq", "1")),
            List.of(
                new AdminAutomationRulesController.ActionDto("auto_assign_rider", Map.of(), false)),
            new AdminAutomationRulesController.GuardrailsDto(
                new AdminAutomationRulesController.RateLimitDto(1, 60),
                100L,
                null,
                true,
                "open_csm_task"),
            300);
    controller.create(principal, createBody);
    verify(rules)
        .create(
            any(), eq("n"), eq("d"), eq("order_unassigned"), any(), any(), any(), any(), eq(300));
  }
}

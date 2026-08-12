package com.nammamedmate.automation.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.WorkflowManagementService;
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
class AdminAutomationWorkflowsControllerTest {

  @Mock WorkflowManagementService workflows;
  @InjectMocks AdminAutomationWorkflowsController controller;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @Test
  void delegatesAllEndpoints() {
    UUID id = UUID.randomUUID();
    UUID execId = UUID.randomUUID();
    when(workflows.list(any())).thenReturn(Map.of("workflows", List.of()));
    when(workflows.create(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("status", "INACTIVE"));
    when(workflows.get(any(), eq(id))).thenReturn(Map.of("id", id));
    when(workflows.patch(any(), eq(id), any(), any(), any(), any()))
        .thenReturn(Map.of("version", 2));
    when(workflows.toggle(any(), eq(id), any())).thenReturn(Map.of("status", "ACTIVE"));
    when(workflows.listExecutions(any(), eq(id), any(), any()))
        .thenReturn(
            new WorkflowManagementService.PagedResult(
                Map.of("executions", List.of()), PaginationMeta.of(1, 20, 0)));
    when(workflows.cancel(any(), eq(id), eq(execId))).thenReturn(Map.of("status", "CANCELLED"));

    assertThat(controller.list(principal).success()).isTrue();
    assertThat(controller.create(principal, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(controller.get(principal, id).data()).containsEntry("id", id);
    assertThat(controller.patch(principal, id, null).success()).isTrue();
    assertThat(
            controller
                .toggle(
                    principal, id, new AdminAutomationWorkflowsController.ToggleRequest("ACTIVE"))
                .data())
        .containsEntry("status", "ACTIVE");
    assertThat(controller.listExecutions(principal, id, null, 1).success()).isTrue();
    assertThat(controller.cancel(principal, id, execId).data())
        .containsEntry("status", "CANCELLED");

    var body =
        new AdminAutomationWorkflowsController.CreateWorkflowRequest(
            "WF",
            "d",
            "invoice_overdue",
            List.of(
                new AdminAutomationWorkflowsController.StepDto(
                    "s1", "ACTION", "send_notification", Map.of(), null, null, "s2", null),
                new AdminAutomationWorkflowsController.StepDto(
                    "s2", "WAIT", null, null, 24, null, null, null),
                new AdminAutomationWorkflowsController.StepDto(
                    "s3",
                    "BRANCH",
                    null,
                    null,
                    null,
                    new AdminAutomationWorkflowsController.ConditionDto("a", "eq", true),
                    null,
                    null)));
    controller.create(principal, body);
    verify(workflows).create(any(), eq("WF"), eq("d"), eq("invoice_overdue"), any());
  }
}

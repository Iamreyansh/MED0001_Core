package com.nammamedmate.automation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.port.out.ActionRegistryPort;
import com.nammamedmate.automation.application.port.out.TriggerRegistryPort;
import com.nammamedmate.automation.application.port.out.WorkflowExecutionPort;
import com.nammamedmate.automation.application.port.out.WorkflowStorePort;
import com.nammamedmate.automation.domain.ActionDefinition;
import com.nammamedmate.automation.domain.AutomationWorkflow;
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.automation.domain.StepType;
import com.nammamedmate.automation.domain.TriggerDefinition;
import com.nammamedmate.automation.domain.WorkflowExecution;
import com.nammamedmate.automation.domain.WorkflowExecutionStatus;
import com.nammamedmate.automation.domain.WorkflowStatus;
import com.nammamedmate.automation.domain.WorkflowStep;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowManagementServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T09:00:00Z");
  private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID WF_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

  @Mock WorkflowStorePort store;
  @Mock WorkflowExecutionPort executions;
  @Mock TriggerRegistryPort triggers;
  @Mock ActionRegistryPort actions;

  private WorkflowManagementService service;
  private MedmatePrincipal superAdmin;
  private MedmatePrincipal opsAdmin;

  @BeforeEach
  void setUp() {
    service =
        new WorkflowManagementService(
            store, executions, triggers, actions, Clock.fixed(NOW, ZoneOffset.UTC));
    superAdmin = new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    opsAdmin = new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    when(triggers.findById("invoice_overdue"))
        .thenReturn(
            Optional.of(
                new TriggerDefinition(
                    "invoice_overdue",
                    "FINANCE",
                    "Invoice Overdue",
                    "d",
                    List.of(),
                    List.of("amount_gt"),
                    List.of("invoice.id"),
                    true)));
    when(actions.findById("send_notification"))
        .thenReturn(
            Optional.of(
                new ActionDefinition(
                    "send_notification",
                    "NOTIFICATION",
                    "Send",
                    "d",
                    List.of(),
                    List.of(),
                    false,
                    false,
                    null)));
  }

  @Test
  void ac001_stepLimitExceeded() {
    List<WorkflowStep> steps =
        IntStream.rangeClosed(1, 21)
            .mapToObj(
                i ->
                    new WorkflowStep(
                        "s" + i,
                        StepType.ACTION,
                        "send_notification",
                        Map.of(),
                        null,
                        null,
                        i < 21 ? "s" + (i + 1) : null,
                        null))
            .toList();
    assertThatThrownBy(() -> service.create(superAdmin, "TooBig", "d", "invoice_overdue", steps))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("STEP_LIMIT_EXCEEDED");
  }

  @Test
  void ac004_patchPausesActiveExecutions() {
    when(store.findById(WF_ID)).thenReturn(Optional.of(sampleWorkflow(WorkflowStatus.ACTIVE)));
    when(executions.pauseRunning(WF_ID)).thenReturn(4);
    Map<String, Object> data = service.patch(superAdmin, WF_ID, null, "updated", null, null);
    assertThat(data.get("active_executions_paused")).isEqualTo(4);
    assertThat(data.get("version")).isEqualTo(2);
    assertThat(data.get("status")).isEqualTo("INACTIVE");
    ArgumentCaptor<AutomationWorkflow> cap = ArgumentCaptor.forClass(AutomationWorkflow.class);
    verify(store).update(cap.capture());
    assertThat(cap.getValue().version()).isEqualTo(2);
    assertThat(cap.getValue().status()).isEqualTo(WorkflowStatus.INACTIVE);
  }

  @Test
  void ac006_listExecutionsIncludesWaitUntil() {
    when(store.findById(WF_ID)).thenReturn(Optional.of(sampleWorkflow(WorkflowStatus.ACTIVE)));
    Instant waitUntil = Instant.parse("2026-07-24T10:00:00Z");
    UUID execId = UUID.randomUUID();
    UUID entityId = UUID.randomUUID();
    when(executions.count(WF_ID, null)).thenReturn(1L);
    when(executions.list(eq(WF_ID), eq(null), eq(0), eq(20)))
        .thenReturn(
            List.of(
                new WorkflowExecution(
                    execId,
                    WF_ID,
                    1,
                    "PHARMACY",
                    entityId,
                    "Medplus",
                    "s2",
                    WorkflowExecutionStatus.RUNNING,
                    waitUntil,
                    Map.of(),
                    NOW,
                    null,
                    NOW,
                    List.of())));
    var result = service.listExecutions(superAdmin, WF_ID, null, 1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("executions");
    assertThat(items.getFirst().get("wait_until")).isEqualTo(waitUntil.toString());
    assertThat(items.getFirst().get("current_step_type")).isEqualTo("WAIT");
  }

  @Test
  void ac007_toggleForbiddenForOps() {
    assertThatThrownBy(() -> service.toggle(opsAdmin, WF_ID, "ACTIVE"))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("FORBIDDEN");
              assertThat(ae.httpStatus()).isEqualTo(403);
            });
    verify(store, never()).update(any());
  }

  @Test
  void ac008_cancelStopsWithoutRollback() {
    when(store.findById(WF_ID)).thenReturn(Optional.of(sampleWorkflow(WorkflowStatus.ACTIVE)));
    UUID execId = UUID.randomUUID();
    List<Map<String, Object>> history =
        List.of(Map.of("step_id", "s1", "type", "ACTION", "executed_at", NOW.toString()));
    when(executions.findById(execId))
        .thenReturn(
            Optional.of(
                new WorkflowExecution(
                    execId,
                    WF_ID,
                    1,
                    "PHARMACY",
                    UUID.randomUUID(),
                    "Shop",
                    "s2",
                    WorkflowExecutionStatus.RUNNING,
                    Instant.parse("2026-07-25T09:00:00Z"),
                    Map.of(),
                    NOW,
                    null,
                    NOW,
                    history)));
    Map<String, Object> data = service.cancel(superAdmin, WF_ID, execId);
    assertThat(data.get("status")).isEqualTo("CANCELLED");
    assertThat(data.get("step_history")).isEqualTo(history);
    ArgumentCaptor<WorkflowExecution> cap = ArgumentCaptor.forClass(WorkflowExecution.class);
    verify(executions).update(cap.capture());
    assertThat(cap.getValue().status()).isEqualTo(WorkflowExecutionStatus.CANCELLED);
    assertThat(cap.getValue().stepHistory()).isEqualTo(history);
    assertThat(cap.getValue().waitUntil()).isNull();
  }

  @Test
  void createRejectsDuplicateNameAndOrphanAndCycle() {
    when(store.findByNameIgnoreCase("Dup"))
        .thenReturn(Optional.of(sampleWorkflow(WorkflowStatus.INACTIVE)));
    assertThatThrownBy(
            () ->
                service.create(
                    superAdmin,
                    "Dup",
                    "d",
                    "invoice_overdue",
                    List.of(
                        new WorkflowStep(
                            "s1",
                            StepType.ACTION,
                            "send_notification",
                            Map.of(),
                            null,
                            null,
                            null,
                            null))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("WORKFLOW_NAME_EXISTS");

    when(store.findByNameIgnoreCase(any())).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.create(
                    superAdmin,
                    "Orphan",
                    "d",
                    "invoice_overdue",
                    List.of(
                        new WorkflowStep(
                            "s1",
                            StepType.ACTION,
                            "send_notification",
                            Map.of(),
                            null,
                            null,
                            "missing",
                            null))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ORPHAN_STEP");

    assertThatThrownBy(
            () ->
                service.create(
                    superAdmin,
                    "Cycle",
                    "d",
                    "invoice_overdue",
                    List.of(
                        new WorkflowStep(
                            "s1",
                            StepType.ACTION,
                            "send_notification",
                            Map.of(),
                            null,
                            null,
                            "s2",
                            null),
                        new WorkflowStep(
                            "s2",
                            StepType.ACTION,
                            "send_notification",
                            Map.of(),
                            null,
                            null,
                            "s1",
                            null))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CYCLE_DETECTED");
  }

  @Test
  void createGetListToggleHappyPath() {
    when(store.findByNameIgnoreCase(any())).thenReturn(Optional.empty());
    Map<String, Object> created =
        service.create(
            superAdmin,
            "Dunning",
            "d",
            "invoice_overdue",
            List.of(
                new WorkflowStep(
                    "s1",
                    StepType.ACTION,
                    "send_notification",
                    Map.of("t", "x"),
                    null,
                    null,
                    "s2",
                    null),
                new WorkflowStep("s2", StepType.WAIT, null, Map.of(), 24, null, null, null)));
    assertThat(created.get("status")).isEqualTo("INACTIVE");
    assertThat(created.get("steps_count")).isEqualTo(2);
    verify(store).insert(any());

    when(store.findById(WF_ID)).thenReturn(Optional.of(sampleWorkflow(WorkflowStatus.INACTIVE)));
    when(executions.countByWorkflowAndStatus(any(), any())).thenReturn(0L);
    when(executions.avgCompletionHours(WF_ID)).thenReturn(12.5);
    assertThat(service.get(superAdmin, WF_ID)).containsKey("stats");

    when(store.listAll()).thenReturn(List.of(sampleWorkflow(WorkflowStatus.ACTIVE)));
    when(executions.countCompletedSince(any(), any())).thenReturn(2L);
    assertThat(service.list(opsAdmin).get("workflows")).asList().hasSize(1);

    Map<String, Object> toggled = service.toggle(superAdmin, WF_ID, "ACTIVE");
    assertThat(toggled.get("status")).isEqualTo("ACTIVE");
  }

  private AutomationWorkflow sampleWorkflow(WorkflowStatus status) {
    return new AutomationWorkflow(
        WF_ID,
        "DUNNING_LADDER",
        "d",
        "invoice_overdue",
        List.of(
            new WorkflowStep(
                "s1", StepType.ACTION, "send_notification", Map.of(), null, null, "s2", null),
            new WorkflowStep("s2", StepType.WAIT, null, Map.of(), 72, null, "s3", null),
            new WorkflowStep(
                "s3",
                StepType.BRANCH,
                null,
                Map.of(),
                null,
                new ConditionSpec("pharmacy.is_live", "eq", true),
                null,
                null)),
        status,
        1,
        false,
        ADMIN,
        NOW,
        NOW);
  }
}

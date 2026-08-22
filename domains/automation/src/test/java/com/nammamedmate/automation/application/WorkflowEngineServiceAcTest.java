package com.nammamedmate.automation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.KillSwitchPort;
import com.nammamedmate.automation.application.port.out.WorkflowExecutionPort;
import com.nammamedmate.automation.application.port.out.WorkflowStorePort;
import com.nammamedmate.automation.domain.AutomationWorkflow;
import com.nammamedmate.automation.domain.ConditionEvaluator;
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.automation.domain.KillSwitchStatus;
import com.nammamedmate.automation.domain.StepType;
import com.nammamedmate.automation.domain.WorkflowExecution;
import com.nammamedmate.automation.domain.WorkflowExecutionStatus;
import com.nammamedmate.automation.domain.WorkflowStatus;
import com.nammamedmate.automation.domain.WorkflowStep;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
class WorkflowEngineServiceAcTest {

  private static final Instant T0 = Instant.parse("2026-07-24T09:00:00Z");
  private static final UUID WF_ID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID ENTITY = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");

  @Mock WorkflowStorePort workflows;
  @Mock WorkflowExecutionPort executions;
  @Mock ActionExecutorPort actionExecutor;
  @Mock ActivityLogPort activityLog;

  private final AtomicReference<Instant> now = new AtomicReference<>(T0);
  private WorkflowEngineService engine;
  private final List<WorkflowExecution> stored = new ArrayList<>();

  @BeforeEach
  void setUp() {
    Clock clock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    engine =
        new WorkflowEngineService(
            workflows,
            executions,
            actionExecutor,
            activityLog,
            new ConditionEvaluator(clock),
            clock);
    stored.clear();
    when(executions.findRunning(any(), any())).thenReturn(Optional.empty());
    when(actionExecutor.execute(anyString(), anyMap(), anyMap())).thenReturn(UUID.randomUUID());
    when(activityLog.append(anyString(), anyString(), anyString(), anyMap()))
        .thenReturn(UUID.randomUUID());
    org.mockito.Mockito.doAnswer(
            inv -> {
              stored.add(inv.getArgument(0));
              return null;
            })
        .when(executions)
        .insert(any());
    org.mockito.Mockito.doAnswer(
            inv -> {
              WorkflowExecution ex = inv.getArgument(0);
              stored.removeIf(e -> e.id().equals(ex.id()));
              stored.add(ex);
              return null;
            })
        .when(executions)
        .update(any());
  }

  @Test
  void ac002_waitPersistsAndSchedulerResumes() {
    AutomationWorkflow wf =
        workflow(
            List.of(
                new WorkflowStep(
                    "s1", StepType.ACTION, "send_notification", Map.of(), null, null, "s2", null),
                new WorkflowStep("s2", StepType.WAIT, null, Map.of(), 24, null, "s3", null),
                new WorkflowStep(
                    "s3",
                    StepType.ACTION,
                    "send_notification",
                    Map.of("t", "done"),
                    null,
                    null,
                    null,
                    null)));
    when(workflows.findById(WF_ID)).thenReturn(Optional.of(wf));

    Optional<UUID> started = engine.start(wf, "PHARMACY", ENTITY, "Shop", Map.of());
    assertThat(started).isPresent();
    WorkflowExecution waiting =
        stored.stream().filter(e -> e.waitUntil() != null).findFirst().orElseThrow();
    assertThat(waiting.currentStepId()).isEqualTo("s2");
    assertThat(waiting.waitUntil()).isEqualTo(T0.plusSeconds(24 * 3600));
    assertThat(waiting.status()).isEqualTo(WorkflowExecutionStatus.RUNNING);
    verify(actionExecutor, times(1)).execute(eq("send_notification"), anyMap(), anyMap());

    when(executions.listWaitDue(any(), eq(100))).thenReturn(List.of(waiting));
    now.set(waiting.waitUntil().plusSeconds(1));
    assertThat(engine.processDueWaits(100)).isEqualTo(1);
    WorkflowExecution done =
        stored.stream()
            .filter(e -> e.status() == WorkflowExecutionStatus.COMPLETED)
            .findFirst()
            .orElseThrow();
    assertThat(done.stepHistory()).extracting(m -> m.get("step_id")).contains("s2", "s3");
    verify(actionExecutor, times(2)).execute(eq("send_notification"), anyMap(), anyMap());
  }

  @Test
  void ac003_branchRoutesOnLiveCondition() {
    AutomationWorkflow wf =
        workflow(
            List.of(
                new WorkflowStep(
                    "s1",
                    StepType.BRANCH,
                    null,
                    Map.of(),
                    null,
                    new ConditionSpec("pharmacy.is_live", "eq", true),
                    "sTrue",
                    "sFalse"),
                new WorkflowStep(
                    "sTrue",
                    StepType.ACTION,
                    "send_notification",
                    Map.of("path", "true"),
                    null,
                    null,
                    null,
                    null),
                new WorkflowStep(
                    "sFalse",
                    StepType.ACTION,
                    "send_notification",
                    Map.of("path", "false"),
                    null,
                    null,
                    null,
                    null)));

    engine.start(wf, "PHARMACY", ENTITY, "Shop", Map.of("pharmacy", Map.of("is_live", true)));
    ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
    verify(actionExecutor).execute(eq("send_notification"), params.capture(), anyMap());
    assertThat(params.getValue()).containsEntry("path", "true");

    stored.clear();
    org.mockito.Mockito.reset(actionExecutor);
    when(actionExecutor.execute(anyString(), anyMap(), anyMap())).thenReturn(UUID.randomUUID());
    when(executions.findRunning(any(), any())).thenReturn(Optional.empty());

    engine.start(
        wf, "PHARMACY", UUID.randomUUID(), "Shop2", Map.of("pharmacy", Map.of("is_live", false)));
    verify(actionExecutor).execute(eq("send_notification"), eq(Map.of("path", "false")), anyMap());
  }

  @Test
  void ac005_duplicateExecutionSkipped() {
    AutomationWorkflow wf =
        workflow(
            List.of(new WorkflowStep("s1", StepType.WAIT, null, Map.of(), 1, null, null, null)));
    when(executions.findRunning(WF_ID, ENTITY))
        .thenReturn(
            Optional.of(
                new WorkflowExecution(
                    UUID.randomUUID(),
                    WF_ID,
                    1,
                    "PHARMACY",
                    ENTITY,
                    "x",
                    "s1",
                    WorkflowExecutionStatus.RUNNING,
                    T0.plusSeconds(3600),
                    Map.of(),
                    T0,
                    null,
                    T0,
                    List.of())));

    assertThat(engine.start(wf, "PHARMACY", ENTITY, "x", Map.of())).isEmpty();
    verify(executions, never()).insert(any());
    verify(activityLog)
        .append(
            eq("workflow"),
            eq(WorkflowEngineService.DUPLICATE_EXECUTION_SKIPPED),
            anyString(),
            anyMap());

    when(workflows.listActiveByTrigger("invoice_overdue")).thenReturn(List.of(wf));
    assertThat(engine.onTrigger("invoice_overdue", "PHARMACY", ENTITY, "x", Map.of())).isEmpty();
  }

  @Test
  void killSwitchPausedSkipsOnTriggerAndStart() {
    KillSwitchPort kill = mock(KillSwitchPort.class);
    when(kill.status()).thenReturn(KillSwitchStatus.PAUSED);
    WorkflowEngineService paused =
        new WorkflowEngineService(
            workflows,
            executions,
            actionExecutor,
            activityLog,
            new ConditionEvaluator(clock()),
            clock(),
            kill);
    AutomationWorkflow wf =
        workflow(
            List.of(new WorkflowStep("s1", StepType.WAIT, null, Map.of(), 1, null, null, null)));
    when(workflows.listActiveByTrigger("invoice_overdue")).thenReturn(List.of(wf));
    assertThat(paused.onTrigger("invoice_overdue", "PHARMACY", ENTITY, "x", Map.of())).isEmpty();
    assertThat(paused.start(wf, "PHARMACY", ENTITY, "x", Map.of())).isEmpty();
    verify(executions, never()).insert(any());
    verify(activityLog, times(2))
        .append(
            eq("kill_switch"), eq(WorkflowEngineService.KILL_SWITCH_PAUSED), anyString(), anyMap());

    when(kill.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(workflows.listActiveByTrigger("invoice_overdue")).thenReturn(List.of());
    assertThat(paused.onTrigger("invoice_overdue", "PHARMACY", ENTITY, "x", Map.of())).isEmpty();
  }

  private Clock clock() {
    return new Clock() {
      @Override
      public ZoneOffset getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(java.time.ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        return now.get();
      }
    };
  }

  private AutomationWorkflow workflow(List<WorkflowStep> steps) {
    return new AutomationWorkflow(
        WF_ID, "WF", "d", "invoice_overdue", steps, WorkflowStatus.ACTIVE, 1, false, null, T0, T0);
  }
}

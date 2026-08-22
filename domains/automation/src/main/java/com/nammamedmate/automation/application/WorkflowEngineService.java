package com.nammamedmate.automation.application;

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
import com.nammamedmate.automation.domain.WorkflowStep;
import com.nammamedmate.kernel.id.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowEngineService {

  private static final Logger log = LoggerFactory.getLogger(WorkflowEngineService.class);
  public static final String DUPLICATE_EXECUTION_SKIPPED = "DUPLICATE_EXECUTION_SKIPPED";
  public static final String KILL_SWITCH_PAUSED = "KILL_SWITCH_PAUSED";

  private final WorkflowStorePort workflows;
  private final WorkflowExecutionPort executions;
  private final ActionExecutorPort actionExecutor;
  private final ActivityLogPort activityLog;
  private final ConditionEvaluator evaluator;
  private final Clock clock;
  private final KillSwitchPort killSwitch;

  public WorkflowEngineService(
      WorkflowStorePort workflows,
      WorkflowExecutionPort executions,
      ActionExecutorPort actionExecutor,
      ActivityLogPort activityLog,
      ConditionEvaluator evaluator,
      Clock clock) {
    this(workflows, executions, actionExecutor, activityLog, evaluator, clock, null);
  }

  @Autowired
  public WorkflowEngineService(
      WorkflowStorePort workflows,
      WorkflowExecutionPort executions,
      ActionExecutorPort actionExecutor,
      ActivityLogPort activityLog,
      ConditionEvaluator evaluator,
      Clock clock,
      KillSwitchPort killSwitch) {
    this.workflows = workflows;
    this.executions = executions;
    this.actionExecutor = actionExecutor;
    this.activityLog = activityLog;
    this.evaluator = evaluator;
    this.clock = clock;
    this.killSwitch = killSwitch;
  }

  /** Start executions for all ACTIVE workflows matching the trigger (dedup per entity). */
  @Transactional
  public List<UUID> onTrigger(
      String triggerId,
      String entityType,
      UUID entityId,
      String entityName,
      Map<String, Object> context) {
    if (triggerId == null || entityId == null) {
      return List.of();
    }
    if (isPaused()) {
      log.info("{} trigger_id={} entity_id={}", KILL_SWITCH_PAUSED, triggerId, entityId);
      activityLog.append(
          "kill_switch",
          KILL_SWITCH_PAUSED,
          "Kill switch paused",
          Map.of("trigger_id", triggerId, "entity_id", entityId.toString()));
      return List.of();
    }
    List<UUID> started = new ArrayList<>();
    for (AutomationWorkflow wf : workflows.listActiveByTrigger(triggerId)) {
      start(wf, entityType, entityId, entityName, context).ifPresent(started::add);
    }
    return started;
  }

  @Transactional
  public Optional<UUID> start(
      AutomationWorkflow wf,
      String entityType,
      UUID entityId,
      String entityName,
      Map<String, Object> context) {
    if (wf == null || entityId == null) {
      return Optional.empty();
    }
    if (isPaused()) {
      log.info("{} workflow_id={} entity_id={}", KILL_SWITCH_PAUSED, wf.id(), entityId);
      activityLog.append(
          "kill_switch",
          KILL_SWITCH_PAUSED,
          "Kill switch paused",
          Map.of("workflow_id", wf.id().toString(), "entity_id", entityId.toString()));
      return Optional.empty();
    }
    if (executions.findRunning(wf.id(), entityId).isPresent()) {
      log.info("{} workflow_id={} entity_id={}", DUPLICATE_EXECUTION_SKIPPED, wf.id(), entityId);
      activityLog.append(
          "workflow",
          DUPLICATE_EXECUTION_SKIPPED,
          "Duplicate active execution skipped",
          Map.of("workflow_id", wf.id().toString(), "entity_id", entityId.toString()));
      return Optional.empty();
    }
    WorkflowStep entry = wf.entryStep();
    if (entry == null) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    UUID id = Ids.newId();
    WorkflowExecution execution =
        new WorkflowExecution(
            id,
            wf.id(),
            wf.version(),
            entityType == null ? "UNKNOWN" : entityType,
            entityId,
            entityName,
            entry.stepId(),
            WorkflowExecutionStatus.RUNNING,
            null,
            context == null ? Map.of() : context,
            now,
            null,
            null,
            List.of());
    executions.insert(execution);
    advanceUntilBlocked(wf, execution);
    return Optional.of(id);
  }

  /** Resume WAIT steps whose wait_until has elapsed. */
  @Transactional
  public int processDueWaits(int limit) {
    Instant now = clock.instant();
    int advanced = 0;
    for (WorkflowExecution due : executions.listWaitDue(now, limit)) {
      AutomationWorkflow wf = workflows.findById(due.workflowId()).orElse(null);
      if (wf == null) {
        continue;
      }
      WorkflowStep current = wf.stepById(due.currentStepId());
      if (current == null || current.type() != StepType.WAIT) {
        continue;
      }
      String next = current.nextStepIdOnTrue();
      List<Map<String, Object>> history = new ArrayList<>(due.stepHistory());
      history.add(historyRow(current.stepId(), "WAIT", now, Map.of("resumed", true)));
      WorkflowExecution resumed =
          new WorkflowExecution(
              due.id(),
              due.workflowId(),
              due.workflowVersion(),
              due.entityType(),
              due.entityId(),
              due.entityName(),
              next,
              next == null ? WorkflowExecutionStatus.COMPLETED : WorkflowExecutionStatus.RUNNING,
              null,
              due.context(),
              due.startedAt(),
              next == null ? now : null,
              now,
              history);
      executions.update(resumed);
      if (next != null) {
        advanceUntilBlocked(wf, resumed);
      }
      advanced++;
    }
    return advanced;
  }

  private void advanceUntilBlocked(AutomationWorkflow wf, WorkflowExecution start) {
    WorkflowExecution current = start;
    // Safety: max steps * 2 iterations to avoid infinite loops if graph is corrupt.
    for (int i = 0; i < WorkflowStepValidatorGuard.MAX_ITERS; i++) {
      if (current.status() != WorkflowExecutionStatus.RUNNING) {
        return;
      }
      if (current.waitUntil() != null) {
        return;
      }
      String stepId = current.currentStepId();
      if (stepId == null) {
        complete(current, clock.instant());
        return;
      }
      WorkflowStep step = wf.stepById(stepId);
      if (step == null) {
        fail(current, clock.instant());
        return;
      }
      Instant now = clock.instant();
      if (step.type() == StepType.WAIT) {
        Instant until = now.plus(Duration.ofHours(step.waitDurationHours()));
        WorkflowExecution waiting =
            new WorkflowExecution(
                current.id(),
                current.workflowId(),
                current.workflowVersion(),
                current.entityType(),
                current.entityId(),
                current.entityName(),
                step.stepId(),
                WorkflowExecutionStatus.RUNNING,
                until,
                current.context(),
                current.startedAt(),
                null,
                now,
                current.stepHistory());
        executions.update(waiting);
        return;
      }
      if (step.type() == StepType.ACTION) {
        try {
          actionExecutor.execute(
              step.actionId(),
              step.params(),
              Map.of(
                  "execution_id",
                  current.id().toString(),
                  "entity_id",
                  current.entityId().toString(),
                  "workflow_id",
                  wf.id().toString()));
        } catch (RuntimeException ex) {
          log.warn("Workflow action failed step={} err={}", step.stepId(), ex.toString());
        }
        List<Map<String, Object>> history = new ArrayList<>(current.stepHistory());
        history.add(
            historyRow(
                step.stepId(),
                "ACTION",
                now,
                Map.of("action_id", step.actionId() == null ? "" : step.actionId())));
        String next = step.nextStepIdOnTrue();
        current = move(current, next, history, now);
        executions.update(current);
        continue;
      }
      // BRANCH
      ConditionSpec cond = step.condition();
      boolean met =
          evaluator.evaluate(cond == null ? List.of() : List.of(cond), current.context()).met();
      String next = met ? step.nextStepIdOnTrue() : step.nextStepIdOnFalse();
      List<Map<String, Object>> history = new ArrayList<>(current.stepHistory());
      history.add(historyRow(step.stepId(), "BRANCH", now, Map.of("result", met)));
      current = move(current, next, history, now);
      executions.update(current);
    }
  }

  private WorkflowExecution move(
      WorkflowExecution current,
      String nextStepId,
      List<Map<String, Object>> history,
      Instant now) {
    if (nextStepId == null || nextStepId.isBlank()) {
      return new WorkflowExecution(
          current.id(),
          current.workflowId(),
          current.workflowVersion(),
          current.entityType(),
          current.entityId(),
          current.entityName(),
          current.currentStepId(),
          WorkflowExecutionStatus.COMPLETED,
          null,
          current.context(),
          current.startedAt(),
          now,
          now,
          history);
    }
    return new WorkflowExecution(
        current.id(),
        current.workflowId(),
        current.workflowVersion(),
        current.entityType(),
        current.entityId(),
        current.entityName(),
        nextStepId,
        WorkflowExecutionStatus.RUNNING,
        null,
        current.context(),
        current.startedAt(),
        null,
        now,
        history);
  }

  private void complete(WorkflowExecution current, Instant now) {
    executions.update(
        new WorkflowExecution(
            current.id(),
            current.workflowId(),
            current.workflowVersion(),
            current.entityType(),
            current.entityId(),
            current.entityName(),
            current.currentStepId(),
            WorkflowExecutionStatus.COMPLETED,
            null,
            current.context(),
            current.startedAt(),
            now,
            now,
            current.stepHistory()));
  }

  private void fail(WorkflowExecution current, Instant now) {
    executions.update(
        new WorkflowExecution(
            current.id(),
            current.workflowId(),
            current.workflowVersion(),
            current.entityType(),
            current.entityId(),
            current.entityName(),
            current.currentStepId(),
            WorkflowExecutionStatus.FAILED,
            null,
            current.context(),
            current.startedAt(),
            now,
            now,
            current.stepHistory()));
  }

  private boolean isPaused() {
    return killSwitch != null && killSwitch.status() == KillSwitchStatus.PAUSED;
  }

  private static Map<String, Object> historyRow(
      String stepId, String type, Instant at, Map<String, Object> extra) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("step_id", stepId);
    row.put("type", type);
    row.put("executed_at", at.toString());
    row.putAll(extra);
    return row;
  }

  /** Tiny holder so engine doesn't depend on validator MAX constant name. */
  private static final class WorkflowStepValidatorGuard {
    static final int MAX_ITERS = 40;

    private WorkflowStepValidatorGuard() {}
  }
}

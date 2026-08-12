package com.nammamedmate.automation.application;

import com.nammamedmate.automation.application.port.out.ActionRegistryPort;
import com.nammamedmate.automation.application.port.out.TriggerRegistryPort;
import com.nammamedmate.automation.application.port.out.WorkflowExecutionPort;
import com.nammamedmate.automation.application.port.out.WorkflowStorePort;
import com.nammamedmate.automation.domain.AutomationWorkflow;
import com.nammamedmate.automation.domain.StepType;
import com.nammamedmate.automation.domain.TriggerDefinition;
import com.nammamedmate.automation.domain.WorkflowExecution;
import com.nammamedmate.automation.domain.WorkflowExecutionStatus;
import com.nammamedmate.automation.domain.WorkflowStatus;
import com.nammamedmate.automation.domain.WorkflowStep;
import com.nammamedmate.automation.domain.WorkflowStepValidator;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowManagementService {

  private final WorkflowStorePort store;
  private final WorkflowExecutionPort executions;
  private final TriggerRegistryPort triggers;
  private final ActionRegistryPort actions;
  private final Clock clock;

  public WorkflowManagementService(
      WorkflowStorePort store,
      WorkflowExecutionPort executions,
      TriggerRegistryPort triggers,
      ActionRegistryPort actions,
      Clock clock) {
    this.store = store;
    this.executions = executions;
    this.triggers = triggers;
    this.actions = actions;
    this.clock = clock;
  }

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {
    public PagedResult {
      data = Map.copyOf(data);
    }
  }

  public Map<String, Object> list(MedmatePrincipal principal) {
    requireAdmin(principal);
    Instant dayStart =
        LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC);
    List<Map<String, Object>> items = new ArrayList<>();
    for (AutomationWorkflow wf : store.listAll()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", wf.id());
      row.put("name", wf.name());
      row.put("description", wf.description());
      row.put("trigger_id", wf.triggerId());
      row.put("steps_count", wf.steps().size());
      row.put(
          "active_executions",
          executions.countByWorkflowAndStatus(wf.id(), WorkflowExecutionStatus.RUNNING));
      row.put("completed_today", executions.countCompletedSince(wf.id(), dayStart));
      row.put("status", wf.status().name());
      row.put("version", wf.version());
      row.put("created_at", wf.createdAt().toString());
      items.add(row);
    }
    return Map.of("workflows", items);
  }

  @Transactional
  public Map<String, Object> create(
      MedmatePrincipal principal,
      String name,
      String description,
      String triggerId,
      List<WorkflowStep> steps) {
    requireAdmin(principal);
    String trimmed = requireName(name);
    if (store.findByNameIgnoreCase(trimmed).isPresent()) {
      throw new AppException("WORKFLOW_NAME_EXISTS", "Workflow name already exists", 409);
    }
    requireTrigger(triggerId);
    List<WorkflowStep> validated = steps == null ? List.of() : steps;
    WorkflowStepValidator.validate(validated);
    validateActionRefs(validated);
    Instant now = clock.instant();
    UUID id = Ids.newId();
    AutomationWorkflow wf =
        new AutomationWorkflow(
            id,
            trimmed,
            description,
            triggerId,
            validated,
            WorkflowStatus.INACTIVE,
            1,
            false,
            principal.subject(),
            now,
            now);
    store.insert(wf);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id);
    data.put("name", trimmed);
    data.put("status", WorkflowStatus.INACTIVE.name());
    data.put("steps_count", validated.size());
    data.put("version", 1);
    data.put("created_at", now.toString());
    return data;
  }

  public Map<String, Object> get(MedmatePrincipal principal, UUID id) {
    requireAdmin(principal);
    AutomationWorkflow wf = requireWorkflow(id);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", wf.id());
    data.put("name", wf.name());
    data.put("description", wf.description());
    data.put("trigger_id", wf.triggerId());
    data.put("status", wf.status().name());
    data.put("version", wf.version());
    data.put("steps", stepsPayload(wf.steps()));
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put(
        "active_executions",
        executions.countByWorkflowAndStatus(id, WorkflowExecutionStatus.RUNNING));
    stats.put(
        "completed_all_time",
        executions.countByWorkflowAndStatus(id, WorkflowExecutionStatus.COMPLETED));
    stats.put(
        "cancelled_all_time",
        executions.countByWorkflowAndStatus(id, WorkflowExecutionStatus.CANCELLED));
    Double avg = executions.avgCompletionHours(id);
    stats.put("avg_completion_hours", avg == null ? 0 : Math.round(avg));
    data.put("stats", stats);
    return data;
  }

  @Transactional
  public Map<String, Object> patch(
      MedmatePrincipal principal,
      UUID id,
      String name,
      String description,
      String triggerId,
      List<WorkflowStep> steps) {
    requireAdmin(principal);
    AutomationWorkflow existing = requireWorkflow(id);
    String newName = name == null ? existing.name() : requireName(name);
    if (!newName.equalsIgnoreCase(existing.name())
        && store.findByNameIgnoreCase(newName).isPresent()) {
      throw new AppException("WORKFLOW_NAME_EXISTS", "Workflow name already exists", 409);
    }
    String newTrigger = triggerId == null ? existing.triggerId() : triggerId;
    requireTrigger(newTrigger);
    List<WorkflowStep> newSteps = steps == null ? existing.steps() : steps;
    WorkflowStepValidator.validate(newSteps);
    validateActionRefs(newSteps);
    int paused = executions.pauseRunning(id);
    Instant now = clock.instant();
    AutomationWorkflow updated =
        new AutomationWorkflow(
            existing.id(),
            newName,
            description == null ? existing.description() : description,
            newTrigger,
            newSteps,
            WorkflowStatus.INACTIVE,
            existing.version() + 1,
            existing.seedWorkflow(),
            existing.createdBy(),
            existing.createdAt(),
            now);
    store.update(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id);
    data.put("version", updated.version());
    data.put("active_executions_paused", paused);
    data.put("status", WorkflowStatus.INACTIVE.name());
    data.put("updated_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> toggle(MedmatePrincipal principal, UUID id, String statusRaw) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Only admin_super can toggle workflows", 403);
    }
    WorkflowStatus next;
    try {
      next = WorkflowStatus.parse(statusRaw);
    } catch (RuntimeException ex) {
      throw new AppException("VALIDATION_ERROR", "Invalid status", 422);
    }
    if (next == null) {
      throw new AppException("VALIDATION_ERROR", "status is required", 422);
    }
    AutomationWorkflow existing = requireWorkflow(id);
    Instant now = clock.instant();
    AutomationWorkflow updated =
        new AutomationWorkflow(
            existing.id(),
            existing.name(),
            existing.description(),
            existing.triggerId(),
            existing.steps(),
            next,
            existing.version(),
            existing.seedWorkflow(),
            existing.createdBy(),
            existing.createdAt(),
            now);
    store.update(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id);
    data.put("status", next.name());
    data.put("updated_at", now.toString());
    return data;
  }

  public PagedResult listExecutions(
      MedmatePrincipal principal, UUID workflowId, String statusRaw, Integer page) {
    requireAdmin(principal);
    requireWorkflow(workflowId);
    WorkflowExecutionStatus status = null;
    if (statusRaw != null && !statusRaw.isBlank()) {
      try {
        status = WorkflowExecutionStatus.parse(statusRaw);
      } catch (RuntimeException ex) {
        throw new AppException("VALIDATION_ERROR", "Invalid status filter", 422);
      }
    }
    int p = page == null || page < 1 ? 1 : page;
    int lim = 20;
    long total = executions.count(workflowId, status);
    List<WorkflowExecution> rows = executions.list(workflowId, status, (p - 1) * lim, lim);
    List<Map<String, Object>> items = new ArrayList<>();
    AutomationWorkflow wf = store.findById(workflowId).orElseThrow();
    for (WorkflowExecution ex : rows) {
      items.add(executionItem(ex, wf));
    }
    return new PagedResult(Map.of("executions", items), PaginationMeta.of(p, lim, total));
  }

  @Transactional
  public Map<String, Object> cancel(MedmatePrincipal principal, UUID workflowId, UUID executionId) {
    requireAdmin(principal);
    requireWorkflow(workflowId);
    WorkflowExecution existing =
        executions
            .findById(executionId)
            .orElseThrow(() -> new AppException("NOT_FOUND", "Execution not found", 404));
    if (!existing.workflowId().equals(workflowId)) {
      throw new AppException("NOT_FOUND", "Execution not found", 404);
    }
    if (existing.status() == WorkflowExecutionStatus.CANCELLED
        || existing.status() == WorkflowExecutionStatus.COMPLETED) {
      throw new AppException("VALIDATION_ERROR", "Execution already terminal", 422);
    }
    Instant now = clock.instant();
    WorkflowExecution cancelled =
        new WorkflowExecution(
            existing.id(),
            existing.workflowId(),
            existing.workflowVersion(),
            existing.entityType(),
            existing.entityId(),
            existing.entityName(),
            existing.currentStepId(),
            WorkflowExecutionStatus.CANCELLED,
            null,
            existing.context(),
            existing.startedAt(),
            now,
            existing.lastStepExecutedAt(),
            existing.stepHistory());
    executions.update(cancelled);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("execution_id", executionId);
    data.put("status", WorkflowExecutionStatus.CANCELLED.name());
    data.put("cancelled_at", now.toString());
    data.put("current_step", existing.currentStepId());
    data.put("step_history", existing.stepHistory());
    return data;
  }

  private Map<String, Object> executionItem(WorkflowExecution ex, AutomationWorkflow wf) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("execution_id", ex.id());
    m.put("entity_type", ex.entityType());
    m.put("entity_id", ex.entityId());
    m.put("entity_name", ex.entityName());
    m.put("started_at", ex.startedAt().toString());
    m.put("current_step", ex.currentStepId());
    WorkflowStep step = wf.stepById(ex.currentStepId());
    m.put("current_step_type", step == null ? null : step.type().name());
    m.put("wait_until", ex.waitUntil() == null ? null : ex.waitUntil().toString());
    m.put("status", ex.status().name());
    m.put("workflow_version", ex.workflowVersion());
    return m;
  }

  private static List<Map<String, Object>> stepsPayload(List<WorkflowStep> steps) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (WorkflowStep s : steps) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("step_id", s.stepId());
      row.put("type", s.type().name());
      if (s.type() == StepType.ACTION) {
        row.put("action_id", s.actionId());
        row.put("params", s.params());
      }
      if (s.type() == StepType.WAIT) {
        row.put("wait_duration_hours", s.waitDurationHours());
      }
      if (s.type() == StepType.BRANCH && s.condition() != null) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("field", s.condition().field());
        c.put("operator", s.condition().operator());
        c.put("value", s.condition().value());
        row.put("condition", c);
      }
      row.put("next_step_id_on_true", s.nextStepIdOnTrue());
      row.put("next_step_id_on_false", s.nextStepIdOnFalse());
      out.add(row);
    }
    return out;
  }

  private void validateActionRefs(List<WorkflowStep> steps) {
    for (WorkflowStep s : steps) {
      if (s.type() == StepType.ACTION && actions.findById(s.actionId()).isEmpty()) {
        throw new AppException("INVALID_ACTION", "action_id not in registry", 422);
      }
    }
  }

  private TriggerDefinition requireTrigger(String triggerId) {
    return triggers
        .findById(triggerId)
        .filter(TriggerDefinition::active)
        .orElseThrow(() -> new AppException("INVALID_TRIGGER", "trigger_id not in registry", 422));
  }

  private AutomationWorkflow requireWorkflow(UUID id) {
    return store
        .findById(id)
        .orElseThrow(() -> new AppException("NOT_FOUND", "Workflow not found", 404));
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "name is required", 422);
    }
    return name.trim();
  }

  private static void requireAdmin(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.ADMIN_SUPER && principal.role() != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }
}

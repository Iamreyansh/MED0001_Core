package com.nammamedmate.automation.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates workflow step graphs: size, orphan refs, cycles. */
public final class WorkflowStepValidator {

  public static final int MAX_STEPS = 20;

  private WorkflowStepValidator() {}

  public static void validate(List<WorkflowStep> steps) {
    if (steps == null || steps.isEmpty()) {
      throw new AppException("VALIDATION_ERROR", "steps are required", 422);
    }
    if (steps.size() > MAX_STEPS) {
      throw new AppException("STEP_LIMIT_EXCEEDED", "A workflow may have at most 20 steps", 422);
    }
    Set<String> ids = new HashSet<>();
    for (WorkflowStep s : steps) {
      if (s == null || s.stepId() == null || s.stepId().isBlank()) {
        throw new AppException("VALIDATION_ERROR", "step_id is required", 422);
      }
      if (s.type() == null) {
        throw new AppException("VALIDATION_ERROR", "step type is required", 422);
      }
      if (!ids.add(s.stepId())) {
        throw new AppException("VALIDATION_ERROR", "Duplicate step_id: " + s.stepId(), 422);
      }
      if (s.type() == StepType.ACTION) {
        if (s.actionId() == null || s.actionId().isBlank()) {
          throw new AppException("VALIDATION_ERROR", "action_id required for ACTION", 422);
        }
      } else if (s.type() == StepType.WAIT) {
        if (s.waitDurationHours() == null || s.waitDurationHours() < 0) {
          throw new AppException("VALIDATION_ERROR", "wait_duration_hours required for WAIT", 422);
        }
      } else {
        // BRANCH
        if (s.condition() == null
            || s.condition().operator() == null
            || s.condition().operator().isBlank()) {
          throw new AppException("VALIDATION_ERROR", "condition required for BRANCH", 422);
        }
      }
    }
    for (WorkflowStep s : steps) {
      requireKnown(ids, s.nextStepIdOnTrue());
      requireKnown(ids, s.nextStepIdOnFalse());
    }
    detectCycle(steps);
  }

  private static void requireKnown(Set<String> ids, String next) {
    if (next == null || next.isBlank()) {
      return;
    }
    if (!ids.contains(next)) {
      throw new AppException("ORPHAN_STEP", "next_step_id references unknown step: " + next, 422);
    }
  }

  /** DFS cycle detection on directed edges from next_step_id_on_true/false. */
  private static void detectCycle(List<WorkflowStep> steps) {
    Map<String, List<String>> adj = new HashMap<>();
    for (WorkflowStep s : steps) {
      List<String> outs = adj.computeIfAbsent(s.stepId(), k -> new ArrayList<>());
      if (s.nextStepIdOnTrue() != null && !s.nextStepIdOnTrue().isBlank()) {
        outs.add(s.nextStepIdOnTrue());
      }
      if (s.nextStepIdOnFalse() != null && !s.nextStepIdOnFalse().isBlank()) {
        outs.add(s.nextStepIdOnFalse());
      }
    }
    Set<String> visiting = new HashSet<>();
    Set<String> visited = new HashSet<>();
    for (WorkflowStep s : steps) {
      if (hasCycle(s.stepId(), adj, visiting, visited)) {
        throw new AppException("CYCLE_DETECTED", "Workflow steps form a cycle", 422);
      }
    }
  }

  private static boolean hasCycle(
      String node, Map<String, List<String>> adj, Set<String> visiting, Set<String> visited) {
    if (visited.contains(node)) {
      return false;
    }
    if (!visiting.add(node)) {
      return true;
    }
    for (String next : adj.getOrDefault(node, List.of())) {
      if (hasCycle(next, adj, visiting, visited)) {
        return true;
      }
    }
    visiting.remove(node);
    visited.add(node);
    return false;
  }
}

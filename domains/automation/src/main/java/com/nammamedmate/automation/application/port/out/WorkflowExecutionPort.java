package com.nammamedmate.automation.application.port.out;

import com.nammamedmate.automation.domain.WorkflowExecution;
import com.nammamedmate.automation.domain.WorkflowExecutionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowExecutionPort {

  void insert(WorkflowExecution execution);

  void update(WorkflowExecution execution);

  Optional<WorkflowExecution> findById(UUID id);

  Optional<WorkflowExecution> findRunning(UUID workflowId, UUID entityId);

  long countByWorkflowAndStatus(UUID workflowId, WorkflowExecutionStatus status);

  long countCompletedSince(UUID workflowId, Instant since);

  Double avgCompletionHours(UUID workflowId);

  int pauseRunning(UUID workflowId);

  List<WorkflowExecution> list(
      UUID workflowId, WorkflowExecutionStatus status, int offset, int limit);

  long count(UUID workflowId, WorkflowExecutionStatus status);

  List<WorkflowExecution> listWaitDue(Instant now, int limit);
}

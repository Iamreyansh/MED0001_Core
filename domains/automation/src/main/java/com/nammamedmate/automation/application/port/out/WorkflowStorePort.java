package com.nammamedmate.automation.application.port.out;

import com.nammamedmate.automation.domain.AutomationWorkflow;
import com.nammamedmate.automation.domain.WorkflowStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowStorePort {

  Optional<AutomationWorkflow> findById(UUID id);

  Optional<AutomationWorkflow> findByNameIgnoreCase(String name);

  List<AutomationWorkflow> listAll();

  List<AutomationWorkflow> listActiveByTrigger(String triggerId);

  void insert(AutomationWorkflow workflow);

  void update(AutomationWorkflow workflow);

  long countByStatus(WorkflowStatus status);
}

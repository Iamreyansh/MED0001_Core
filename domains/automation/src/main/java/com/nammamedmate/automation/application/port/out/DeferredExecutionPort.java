package com.nammamedmate.automation.application.port.out;

import com.nammamedmate.automation.domain.DeferredExecution;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DeferredExecutionPort {

  void enqueue(
      UUID approvalId, String actionType, Map<String, Object> params, Map<String, Object> context);

  List<DeferredExecution> listAll();

  void delete(UUID id);
}

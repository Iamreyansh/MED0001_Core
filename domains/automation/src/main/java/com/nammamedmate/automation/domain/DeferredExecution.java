package com.nammamedmate.automation.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DeferredExecution(
    UUID id,
    UUID approvalId,
    String actionType,
    Map<String, Object> actionParams,
    Map<String, Object> executionContext,
    Instant createdAt) {

  public DeferredExecution {
    actionParams = actionParams == null ? Map.of() : Map.copyOf(actionParams);
    executionContext = executionContext == null ? Map.of() : Map.copyOf(executionContext);
  }
}

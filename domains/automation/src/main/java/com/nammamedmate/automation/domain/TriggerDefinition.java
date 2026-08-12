package com.nammamedmate.automation.domain;

import java.util.List;
import java.util.Map;

public record TriggerDefinition(
    String triggerId,
    String category,
    String name,
    String description,
    List<Map<String, Object>> parameters,
    List<String> availableConditions,
    List<String> availableContextVars,
    boolean active) {

  public TriggerDefinition {
    parameters = parameters == null ? List.of() : List.copyOf(parameters);
    availableConditions =
        availableConditions == null ? List.of() : List.copyOf(availableConditions);
    availableContextVars =
        availableContextVars == null ? List.of() : List.copyOf(availableContextVars);
  }
}

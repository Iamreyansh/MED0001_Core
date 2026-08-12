package com.nammamedmate.automation.domain;

import java.util.List;

public record ActionDefinition(
    String actionId,
    String category,
    String name,
    String description,
    List<String> requiredParams,
    List<String> optionalParams,
    boolean reversible,
    boolean alwaysRequireApproval,
    Long autoApprovalLimitPaise) {

  public ActionDefinition {
    requiredParams = requiredParams == null ? List.of() : List.copyOf(requiredParams);
    optionalParams = optionalParams == null ? List.of() : List.copyOf(optionalParams);
  }
}

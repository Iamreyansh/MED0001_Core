package com.nammamedmate.crm.domain;

import java.util.List;
import java.util.UUID;

public record ModuleMatrixRow(
    UUID id,
    String moduleId,
    String moduleName,
    String moduleCode,
    String groupName,
    List<String> planNames) {

  public ModuleMatrixRow {
    planNames = planNames == null ? List.of() : List.copyOf(planNames);
  }
}
